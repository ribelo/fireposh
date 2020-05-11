(ns ribelo.fireposh.fx
  (:require
   [cljs.core.async :as a :refer [go go-loop chan >! <! timeout alts!]]
   [applied-science.js-interop :as j]
   [cljs-bean.core :refer [bean ->js ->clj]]
   [clojure.walk :refer [postwalk]]
   [datascript.core :as d]
   [posh.reagent :as p]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [ribelo.firenze.firebase :as fb]
   [ribelo.firenze.realtime-database :as rdb]
   [ribelo.firenze.utils :as fu]
   [taoensso.timbre :as timbre]))

(def ^:private ids-map_ (atom {}))
(def ^:private transactor-chan (chan 1))

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
 ::create-connection.from-db
 (fn [db]
   (let [conn (d/conn-from-db db)]
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

(defn- on-child-added [snap]
  (let [conn @re-posh.db/store
        fid  (demunge (j/get snap :key))
        eid  (get (clojure.set/map-invert @ids-map_) fid fid)
        refs (:db.type/ref (:rschema @conn))
        m    (persistent!
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
               (bean (j/call snap :val) :keywordize-keys false)))]
    (go
      (let [t     (timeout 1000)
            [c v] (alts! [t [transactor-chan m]])]
        (if (= c t)
          (timbre/error "transactor channel is full?"))))))

(defn- on-child-removed [snap]
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

(defn create-transactor [size wait]
  (let [conn @re-posh.db/store]
    (go-loop [n size
              tx-data (transient [])]
      (if (> n 0)
        (let [t     (timeout wait)
              [v c] (alts! [transactor-chan t])]
          (if (= c t)
            (recur 0 tx-data)
            (recur (dec n) (conj! tx-data v))))
        (let [{:keys [db-after tx-data tx-meta
                      tempids]
               :as   report} (d/with @conn (persistent! tx-data) ::sync)]
          (reset! conn db-after)
          (doseq [[fid eid] (dissoc tempids :db/current-tx)]
            (when-not (get @ids-map_ eid)
              (swap! ids-map_ assoc eid fid)))
          (doseq [[_ callback] (some-> (:listeners (meta conn)) (deref))]
            (callback report))
          (<! (timeout wait))
          (recur size (transient [])))))))

(rf/reg-fx
 ::create-transactor
 (fn [[size wait]]
   (create-transactor size wait)))
