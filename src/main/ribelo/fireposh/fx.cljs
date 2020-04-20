(ns ribelo.fireposh.fx
  (:require
   [clojure.walk :refer [postwalk]]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [datascript.core :as d]
   [ribelo.firenze.firebase :as fb]
   [ribelo.firenze.realtime-database :as rdb]
   [applied-science.js-interop :as j]
   [cljs-bean.core :refer [bean ->js ->clj]]))

(rf/reg-fx
 ::init-firebase
 (fn [app-info]
   (fb/initialize-app app-info)))

(rf/reg-fx
 ::create-connection.firebase-schema
 (fn [_]
   (-> (rdb/ref [:_meta :schema])
       (j/call :once "value"
               (fn [snap]
                 (let [schema
                       (postwalk (fn [x]
                                   (if (map? x) (into {} (map (fn [[k v]]
                                                                [(keyword (demunge k))
                                                                 (if (string? v) (keyword (demunge v)) v)]) x)) x))
                                 (bean (j/call snap :val) :keywordize-keys false :recursive true))]
                   (rf/dispatch [:ribelo.fireposh.events/create-connection.local-schema schema])))))))

(rf/reg-fx
 ::create-connection.local-schema
 (fn [schema]
   (let [schema' (or schema ( [:_meta :schema]))
         conn    (d/create-conn schema)]
     (rp/connect! conn))))

(rf/reg-fx
 ::set-schema
 (fn [schema]
   (rdb/set [:_meta :schema]
            (postwalk (fn [x]
                        (if (map? x) (into {} (map (fn [[k v]]
                                                     [(munge (->js k))
                                                      (if (keyword? v) (munge (->js v)) v)]) x)) x))
                      schema))))

(rf/reg-fx
 ::link-max-eid
 (fn [_]
   (let [conn @re-posh.db/store]
     (rdb/on :value [:_meta :max-eid] #(swap! conn assoc :max-eid %))
     (d/listen! conn ::link-max-eid
                (fn [{:keys [tx-data]}]
                  (let [max-eid (->> tx-data last first)]
                    (rdb/set [:_meta :max-eid] max-eid)))))))

(rf/reg-fx
 ::link-db
 (fn [_]
   (d/listen! @re-posh.db/store ::link-db
              (fn [{:keys [db-before db-after tx-data tx-meta]}]
                (when-not (= ::sync tx-meta)
                  (doseq [[e a v tx added?] tx-data]
                    (when-let [path (:db/path (d/entity (if added? db-after db-before) e))]
                      (if added?
                        (rdb/set (conj path e a) v)
                        (rdb/remove (conj path e a))))))))))

(defn- on-child-added [conn snap]
  (let [e (js/parseInt (j/get snap :key))
        m (persistent!
           (reduce-kv
            (fn [acc k v]
              (assoc! acc (keyword (demunge k)) (->clj v)))
            (transient {})
            (bean (j/call snap :val) :keywordize-keys false)))]
    (d/transact! conn [(assoc m :db/id e)] ::sync)))

(defn- on-child-changed [conn snap]
  (let [e (j/get snap :key)
        m (persistent!
           (reduce-kv
            (fn [acc k v]
              (assoc! acc (keyword (demunge k)) (->clj v)))
            (transient {})
            (bean (j/call snap :val) :keywordize-keys false)))]
    (d/transact! conn [(assoc m :db/id e)] ::sync)))

(defn- on-child-removed [conn snap]
  (let [e (js/parseInt (j/get snap :key))]
    (d/transact! conn [[:db.fn/retractEntity e]] ::sync)))

(rf/reg-fx
 ::link-paths
 (fn [paths]
   (doseq [path paths]
     (rdb/off path)
     (-> (rdb/ref path)
         (j/call :on "child_added"   (partial on-child-added   @re-posh.db/store)))
     (-> (rdb/ref path)
         (j/call :on "child_changed" (partial on-child-changed @re-posh.db/store)))
     (-> (rdb/ref path)
         (j/call :on "child_removed" (partial on-child-removed @re-posh.db/store))))))

(rf/reg-fx
 ::unlink-paths
 (fn [paths]
   (doseq [path paths]
     (rdb/off path))))
