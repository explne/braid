(ns braid.core.server.sync-helpers
  (:require
   [braid.core.server.bots :as bots]
   [braid.core.server.db.bot :as bot]
   [braid.core.server.db.group :as group]
   [braid.core.server.db.tag :as tag]
   [braid.core.server.db.thread :as thread]
   [braid.core.server.db.user :as user]
   [braid.core.server.email-digest :as email]
   [braid.core.server.message-format :refer [parse-tags-and-mentions]]
   [braid.core.server.notify-rules :as notify-rules]
   [braid.core.server.socket :refer [chsk-send! connected-uids]]
   [clojure.set :refer [difference intersection]]
   [taoensso.timbre :as timbre]))

(def anonymous-group-readers (atom {}))

(defn add-anonymous-reader
  [group-id client-id]
  (swap! anonymous-group-readers update group-id (fnil conj #{}) client-id))

(defn remove-anonymous-reader
  [client-id]
  (let [group (some (fn [[g ids]]
                      (and (contains? ids client-id)
                           g))
                    @anonymous-group-readers)]
    (swap! anonymous-group-readers update group disj client-id)))

(defn broadcast-thread
  "broadcasts thread to all users with the thread open, except those in ids-to-skip"
  [thread-id ids-to-skip]
  (let [user-ids (-> (difference
                       (intersection
                         (set (thread/users-with-thread-open thread-id))
                         (set (:any @connected-uids)))
                       (set ids-to-skip)))
        thread (thread/thread-by-id thread-id)]
    (doseq [uid user-ids]
      (let [user-tags (tag/tag-ids-for-user uid)
            filtered-thread (update-in thread [:tag-ids]
                                       (partial into #{} (filter user-tags)))
            thread-with-last-opens (thread/thread-add-last-open-at
                                     filtered-thread uid)]
        (chsk-send! uid [:braid.client/thread thread-with-last-opens])))
    (doseq [anon-id (@anonymous-group-readers (thread :group-id))]
      (chsk-send! anon-id [:braid.client/thread thread]))))

(defn broadcast-user-change
  "Broadcast user info change to clients that can see this user"
  [user-id info]
  (let [ids-to-send-to (disj
                         (intersection
                           (set (:any @connected-uids))
                           (into
                             #{} (map :id)
                             (user/users-for-user user-id)))
                         user-id)]
    (doseq [uid ids-to-send-to]
      (chsk-send! uid info))))

(defn broadcast-group-change
  "Broadcast group change to clients that are in the group"
  [group-id info]
  (let [ids-to-send-to (intersection
                         (set (:any @connected-uids))
                         (into #{} (map :id)
                               (group/group-users group-id)))]
    (doseq [uid ids-to-send-to]
      (chsk-send! uid info)))
  (doseq [anon-id (@anonymous-group-readers group-id)]
    (chsk-send! anon-id info))
  (doseq [bot (bot/bots-for-event group-id)]
    (bots/send-event-notification bot info)))

; TODO: when using clojure.spec, use spec to validate this
(defn user-can-message? [user-id ?data]
  ; TODO: also check that thread in group
  (every?
      true?
      (concat
        [(or (boolean (thread/user-can-see-thread? user-id (?data :thread-id)))
             (do (timbre/warnf
                   "User %s attempted to add message to disallowed thread %s"
                   user-id (?data :thread-id))
                 false))
         (or (boolean (if-let [cur-group (thread/thread-group-id (?data :thread-id))]
                        (= (?data :group-id) cur-group)
                        true)))]
        (map
          (fn [tag-id]
            (and
              (or (boolean (= (?data :group-id) (tag/tag-group-id tag-id)))
                  (do
                    (timbre/warnf
                      "User %s attempted to add a tag %s from a different group"
                      user-id tag-id)
                    false))
              (or (boolean (tag/user-in-tag-group? user-id tag-id))
                  (do
                    (timbre/warnf "User %s attempted to add a disallowed tag %s"
                                  user-id tag-id)
                    false))))
          (?data :mentioned-tag-ids))
        (map
          (fn [mentioned-id]
            (and
              (or (boolean (group/user-in-group? user-id (?data :group-id)))
                  (do (timbre/warnf
                        "User %s attempted to mention disallowed user %s"
                        user-id mentioned-id)
                      false))
              (or (boolean (user/user-visible-to-user? user-id mentioned-id))
                  (do (timbre/warnf
                        "User %s attempted to mention disallowed user %s"
                        user-id mentioned-id)
                    false))))
          (?data :mentioned-user-ids)))))

(defn notify-bots [new-message]
  ; Notify bots mentioned in the message
  (when-let [bot-name (second (re-find #"^/(\w+)\b" (:content new-message)))]
    (when-let [bot (bot/bot-by-name-in-group bot-name (new-message :group-id))]
      (timbre/debugf "notifying bot %s" bot)
      (bots/send-message-notification bot new-message)))
  ; Notify bots subscribed to the thread
  (doseq [bot (bot/bots-watching-thread (new-message :thread-id))]
    (timbre/debugf "notifying bot %s" bot)
    (bots/send-message-notification bot new-message)))

(defn notify-users [new-message]
  (let [subscribed-user-ids (->>
                              (thread/users-subscribed-to-thread
                                (new-message :thread-id))
                              (remove (partial = (:user-id new-message))))
        online? (intersection
                  (set subscribed-user-ids)
                  (set (:any @connected-uids)))]
    (doseq [uid subscribed-user-ids]
      (when-let [rules (user/user-get-preference uid :notification-rules)]
        (when (notify-rules/notify? uid rules new-message)
          (let [msg (update new-message :content
                            (partial parse-tags-and-mentions uid))]
            (if (online? uid)
              (chsk-send! uid [:braid.client/notify-message msg])
              (let [update-msgs
                    (partial
                      map
                      (fn [m] (update m :content
                                     (partial parse-tags-and-mentions uid))))]
                (-> (email/create-message
                      [(-> (thread/thread-by-id (msg :thread-id))
                           (update :messages update-msgs))])
                    (assoc :subject "Notification from Braid")
                    (->> (email/send-message (user/user-email uid))))))))))))
