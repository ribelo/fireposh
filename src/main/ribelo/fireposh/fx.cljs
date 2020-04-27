(ns ribelo.fireposh.fx
  (:require
   [clojure.walk :refer [postwalk]]
   [taoensso.timbre :as timbre]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [datascript.core :as d]
   [ribelo.firenze.firebase :as fb]
   [ribelo.firenze.realtime-database :as rdb]
   [ribelo.firenze.utils :as fu]
   [applied-science.js-interop :as j]
   [cljs-bean.core :refer [bean ->js ->clj]]))

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
                 (let [schema
                       (postwalk (fn [x]
                                   (if (map? x) (into {} (map (fn [[k v]]
                                                                [(fu/demunge k)
                                                                 (if (string? v) (fu/demunge v) v)]) x)) x))
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
                        (if (map? x)
                          (into {} (map
                                    (fn [[k v]]
                                      [(fu/munge k)
                                       (if (keyword? v) (fu/munge v) v)]) x))
                          x))
                      schema))))

(rf/reg-fx
 ::link-db
 (fn [_]
   (d/listen! @re-posh.db/store ::link-db
              (fn [{:keys [db-after tx-data tx-meta tempids]}]
                (doseq [[_ eid] (dissoc tempids :db/current-tx)]
                  (when-not (get @ids-map_ eid)
                    (swap! ids-map_ assoc eid (str (d/squuid)))))
                (when-not (= ::sync tx-meta)
                  ;; TODO add remove from ids-map_ when retract entity
                  (doseq [eid (into [] (comp (map first) (distinct)) tx-data)]
                    (let [{:keys [db/path]
                           :as   m} (d/pull db-after '[*] eid)]
                      (when path
                        (let [refs (:db.type/ref (:rschema db-after))
                              fid  (get @ids-map_ eid)
                              m'   (persistent!
                                    (reduce-kv
                                     (fn [acc k v]
                                       (if-not (contains? refs k)
                                         (assoc! acc (fu/munge k) (->js v))
                                         (if-let [fid (get @ids-map_ (:db/id v))]
                                           (assoc! acc (fu/munge k) fid)
                                           (do
                                             (timbre/error :link-db (str "no fid for eid: " (:db/id v)))
                                             acc))))
                                     (transient {})
                                     (dissoc m :db/id)))]
                          (rdb/set (conj path fid) (->js m')))))))))))

(defn- on-child-added [conn snap]
  (let [fid               (demunge (j/get snap :key))
        eid               (get (clojure.set/map-invert @ids-map_) fid fid)
        refs              (:db.type/ref (:rschema @conn))
        m                 (persistent!
                           (reduce-kv
                            (fn [acc k v]
                              (let [k* (fu/demunge k)
                                    v* (->clj v)]
                                (if-not (contains? refs k*)
                                  (assoc! acc k* v*)
                                  (if-let [ref-eid (get (clojure.set/map-invert @ids-map_) (demunge v*))]
                                    (assoc! acc k* ref-eid)
                                    (assoc! acc k* v*)))))
                            (transient {:db/id eid})
                            (bean (j/call snap :val) :keywordize-keys false)))
        {:keys [db-after tx-data tx-meta
                tempids]} (d/with @conn [m])]
    (doseq [[fid eid] (dissoc tempids :db/current-tx)]
      (when-not (get @ids-map_ eid)
        (swap! ids-map_ assoc eid fid)))
    ;; TODO listener callback
    (reset! conn db-after)))

(defn- on-child-removed [conn snap]
  (let [fid (demunge (j/get snap :key))]
    (if-let [eid (get (clojure.set/map-invert @ids-map_) fid)]
      (do
        (swap! ids-map_ dissoc eid)
        (d/transact! conn [[:db.fn/retractEntity eid]])) ; with sync, refs must be updated
      (timbre/error :on-child-removed (str "no eid for fid: " fid)))))

(rf/reg-fx
 ::link-paths
 (fn [paths]
   (doseq [path paths]
     (rdb/off path)
     (-> (rdb/ref path)
         (j/call :on "child_added"   (partial on-child-added   @re-posh.db/store)))
     (-> (rdb/ref path)
         (j/call :on "child_changed" (partial on-child-added @re-posh.db/store)))
     (-> (rdb/ref path)
         (j/call :on "child_removed" (partial on-child-removed @re-posh.db/store))))))

(rf/reg-fx
 ::unlink-paths
 (fn [paths]
   (doseq [path paths]
     (rdb/off path))))
