(ns ribelo.fireposh.fx
  (:require
   [applied-science.js-interop :as j]
   [cljs-bean.core :refer [bean ->js ->clj]]
   [clojure.walk :refer [postwalk]]
   [datascript.core :as d]
   [datascript.transit :as dt]
   [posh.reagent :as p]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [ribelo.firenze.firebase :as fb]
   [ribelo.firenze.realtime-database :as rdb]
   [taoensso.encore :as e]
   [taoensso.timbre :as timbre]))

(def ^:private ids-map_ (atom {}))

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
                 (let [schema (dt/read-transit-str (j/call snap :val))]
                   (rf/dispatch [:ribelo.fireposh.events/create-connection.from-schema schema])))))))

(rf/reg-fx
 ::create-connection.from-schema
 (fn [schema]
   (let [conn (d/create-conn schema)]
     (rp/connect! conn))))

(rf/reg-fx
 ::create-connection.from-db
 (fn [db]
   (let [conn (d/conn-from-db db)]
     (rp/connect! conn))))

(rf/reg-fx
 ::set-schema
 (fn [schema]
   (rdb/set [:_meta :schema] (dt/write-transit-str schema))))

(rf/reg-fx
 ::link-db
 (fn [timeout]
   (d/listen! @re-posh.db/store ::link-db
              (fn [{:keys [db-after tx-data tx-meta tempids]}]
                (doseq [[_ eid] (dissoc tempids :db/current-tx)]
                  (when-not (get @ids-map_ eid)
                    (swap! ids-map_ assoc eid (str (d/squuid)))))
                (when-not (= ::sync tx-meta)
                  (doseq [eid (into [] (comp (map first) (distinct)) tx-data)]
                    (let [{:keys [db/path]
                           :as   m} (into {} (d/touch (d/entity eid)))]
                      (when path
                        (let [refs (:db.type/ref (:rschema db-after))
                              fid  (get @ids-map_ eid)
                              m*   (persistent!
                                    (reduce-kv
                                     (fn [acc k v]
                                       (if (contains? refs k)
                                         (assoc! acc k ((:db/id v) @ids-map_))
                                         acc))
                                     (transient m)
                                     refs))]
                          (rdb/set (conj path fid) (dt/write-transit-str m*)))))))))))

(defn- on-child-added [snap]
  (let [conn           @re-posh.db/store
        inv-ids-map    (clojure.set/map-invert @ids-map_)
        fid            (demunge (j/get snap :key))
        eid            (get inv-ids-map fid fid)
        refs           (:db.type/ref (:rschema @conn))
        m              (persistent!
                        (reduce-kv
                         (fn [acc k v]
                           (if (contains? refs k)
                             (assoc! acc k (get inv-ids-map v))
                             (assoc! acc k v)))
                         (transient {:db/id eid})
                         (dt/read-transit-str (j/call snap :val))))
        {:keys [db-after tx-data tx-meta
                tempids]
         :as   report} (d/with @conn [m] ::sync)]
    (reset! conn db-after)
    (doseq [[fid eid] (dissoc tempids :db/current-tx)]
      (when-not (get @ids-map_ eid)
        (swap! ids-map_ assoc eid fid)))
    (doseq [[_ callback] (some-> (:listeners (meta conn)) (deref))]
      (callback report))))

(defn- on-child-removed [snap]
  (let [conn        @re-posh.db/store
        inv-ids-map (clojure.set/map-invert @ids-map_)
        fid         (demunge (j/get snap :key))]
    (when-let [eid (get inv-ids-map fid)]
      (swap! ids-map_ dissoc eid)
      ;; with sync, refs must be updated
      (d/transact! conn [[:db.fn/retractEntity eid]]) )))

(rf/reg-fx
 ::link-paths
 (fn [paths]
   (doseq [path paths]
     (rdb/off path)
     (-> (rdb/ref path)
         (j/call :on "child_added"   on-child-added))
     (-> (rdb/ref path)
         (j/call :on "child_changed" on-child-added))
     (-> (rdb/ref path)
         (j/call :on "child_removed" on-child-removed)))))

(rf/reg-fx
 ::unlink-paths
 (fn [paths]
   (doseq [path paths]
     (rdb/off path))))
