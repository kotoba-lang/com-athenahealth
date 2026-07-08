(ns athenahealth.main
  "Kotodama WASM entrypoint for the Athenahealth clean-room actor (L5) — Clojure port.

  L5 production surface: CRUD + pagination + filtering + relationship
  expansion + strict validation, over a Datomic-backed Kotoba schema.

  py→cljc port of src/main.py (ADR 260607 L4 cohort). Data-driven: every
  handler is a generic fold over `entity-specs`, so the actor's whole REST
  surface is derivable from the schema/manifest. No proprietary code or
  credentials; resource shapes only.

  State lives on the kotoba Datom log: `emit-facts` produces namespaced EAVT
  facts (`athenahealth.<Entity>/<field>`); `*store*` is the in-memory materialization
  used by the contract test and by the WASM runtime before a live engine binds."
  (:require [clojure.string :as str]
            [athenahealth.validation :as validation]))

(def ns-prefix "athenahealth")
(def tier "L5")
(def default-limit 20)
(def max-limit 100)

;; --- schema-derived entity specs (the single source the handlers fold over) ---
(def entity-specs
  [{:entity "Patient"   :plural "patients"   :id-prefix "athenahe_pat"
    :fields [:homeboundyn :assignedsexatbirth :altfirstname :ethnicitycode :industrycode :language6392code :localpatientid :deceaseddate :firstappointment :primaryproviderid :genderidentityother :preferredpronouns :lastappointment :donotcallyn :primarydepartmentid :status :lastemail :racecode :sexualorientation :genderidentity :emailexistsyn :occupationcode :sexualorientationother :patientid]
    :required [:homeboundyn :assignedsexatbirth]
    :coerce {:homeboundyn :bool :donotcallyn :bool :primaryproviderid :int :industrycode :int :primarydepartmentid :int :occupationcode :int :emailexistsyn :bool :patientid :int}
    :refs {}}
   {:entity "Appointment" :plural "appointments" :id-prefix "athenahe_app"
    :fields [:reasonid :appointmentstatus :cancelleddatetime :chargeentrynotrequired :hl7providerid :cancelreasonname :chargeentrynotrequiredreason :lastmodified :departmentid :checkoutdatetime :copay :encounterid :scheduledby :checkindatetime :cancelledby :stopintakedatetime :encounterstatus :frozenyn :appointmenttype :appointmenttypeid :cancelreasonid :cancelreasonnoshow :cancelreasonslotavailable :coordinatorenterpriseyn]
    :required [:reasonid :appointmentstatus]
    :coerce {:reasonid :int :chargeentrynotrequired :bool :hl7providerid :int :departmentid :int :frozenyn :bool :appointmenttypeid :int :cancelreasonid :int :cancelreasonnoshow :bool :cancelreasonslotavailable :bool :coordinatorenterpriseyn :bool}
    :refs {}}
   {:entity "Encounter" :plural "encounters" :id-prefix "athenahe_enc"
    :fields [:encounterid :appointmentid :departmentid :encountervisitname :encountertype :status :patientlocationid :patientlocation :patientstatusid :patientstatus :encounterdate :stage :providerid :providerfirstname :providerlastname :providerphone :lastupdated]
    :required [:encounterid :appointmentid]
    :coerce {:encounterid :int :appointmentid :int :departmentid :int :patientlocationid :int :patientstatusid :int :providerid :int}
    :refs {}}
   {:entity "Claim" :plural "claims" :id-prefix "athenahe_cla"
    :fields [:referringproviderid :claimcreateddate :billedservicedate :billedproviderid :appointmentid :chargeamount :transactionid :claimid]
    :required [:referringproviderid :claimcreateddate]
    :coerce {:referringproviderid :int :billedproviderid :int :appointmentid :int :transactionid :int :claimid :int}
    :refs {}}
   {:entity "Provider" :plural "providers" :id-prefix "athenahe_pro"
    :fields [:billable :ansispecialtycode :firstname :entitytype :otherprovideridlist :ansinamecode :displayname :homedepartment :providerid :providertypeid :providerusername :supervisingproviderid :providertype :createencounterprovideridlist :schedulingname :usualdepartmentid :createencounteroncheckinyn :specialty :hideinportalyn :lastname :npi :providergrouplist :federalidnumber :supervisingproviderusername]
    :required [:billable :ansispecialtycode]
    :coerce {:billable :bool :providerid :int :supervisingproviderid :int :createencounteroncheckinyn :bool :hideinportalyn :bool :npi :int}
    :validate {:npi validation/valid-npi?}
    :refs {}}
   {:entity "Department" :plural "departments" :id-prefix "athenahe_dep"
    :fields [:timezoneoffset :singleappointmentcontractmax :state :placeofservicefacility :latitude :departmentid :address :placeofservicetypeid :longitude :clinicals :timezone :name :patientdepartmentname :chartsharinggroupid :placeofservicetypename :zip :timezonename :communicatorbrandid :medicationhistoryconsent :ishospitaldepartment :providergroupid :portalurl :city :servicedepartment]
    :required [:timezoneoffset :singleappointmentcontractmax]
    :coerce {:timezoneoffset :int :placeofservicefacility :bool :departmentid :int :timezone :int :medicationhistoryconsent :bool :ishospitaldepartment :bool :servicedepartment :bool}
    :refs {}}])

(def entities (mapv :entity entity-specs))

(def routes
  (vec (mapcat (fn [{:keys [plural entity]}]
                 [{:method "POST"   :path (str "/v1/" plural)        :op (str "create " entity) :entity entity}
                  {:method "GET"    :path (str "/v1/" plural)        :op (str "list " entity)   :entity entity}
                  {:method "GET"    :path (str "/v1/" plural "/{id}") :op (str "get " entity)    :entity entity}
                  {:method "PATCH"  :path (str "/v1/" plural "/{id}") :op (str "update " entity) :entity entity}
                  {:method "DELETE" :path (str "/v1/" plural "/{id}") :op (str "delete " entity) :entity entity}])
               entity-specs)))

;; --- platform primitives ---
(defn now []
  #?(:clj (str (java.time.Instant/now))
     :cljs (.toISOString (js/Date.))))

(defn- rand-hex16 []
  #?(:clj (subs (str/replace (str (java.util.UUID/randomUUID)) "-" "") 0 16)
     :cljs (subs (str/replace (str (random-uuid)) "-" "") 0 16)))

(defn new-id [prefix] (str prefix "_" (rand-hex16)))

;; --- coercion ---
(defn as-int [v]
  (cond (number? v) (long v)
        (string? v) (try #?(:clj (Long/parseLong (str/trim v)) :cljs (let [n (js/parseInt v 10)] (if (js/isNaN n) 0 n)))
                         (catch #?(:clj Exception :cljs :default) _ 0))
        :else 0))

(defn as-float [v]
  (cond (number? v) (double v)
        (string? v) (try #?(:clj (Double/parseDouble (str/trim v)) :cljs (let [n (js/parseFloat v)] (if (js/isNaN n) 0.0 n)))
                         (catch #?(:clj Exception :cljs :default) _ 0.0))
        :else 0.0))

(defn as-bool [v]
  (if (nil? v) false (contains? #{"1" "true" "yes" "on" true} (if (string? v) (str/lower-case v) v))))

(defn coerce-field [kind v]
  (case kind :int (as-int v) :float (as-float v) :bool (as-bool v) v))

;; --- in-memory store (materializes the Datom log; live engine binds in prod) ---
(defn fresh-store [] (atom {}))
(def ^:dynamic *store* (fresh-store))

(defn emit-facts
  "EAVT facts for one record: {\"athenahealth.<Entity>/<field>\" v ...}. The datomic
  binding transacts these; the in-memory store keeps the record by id."
  [entity rec]
  (into {} (map (fn [[k v]] [(str ns-prefix "." entity "/" (name k)) v]) rec)))

(defn persist! [store entity rec]
  (swap! store assoc-in [entity (:id rec)] rec)
  rec)

(defn query
  ([store entity] (vec (vals (get @store entity))))
  ([store entity id] (if-let [r (get-in @store [entity id])] [r] [])))

(defn retract! [store entity id] (swap! store update entity dissoc id) {:id id :deleted true})

;; --- validation ---
(defn require-fields [data fields]
  (let [missing (remove #(let [v (get data %)] (and (some? v) (not= v ""))) fields)]
    (when (seq missing)
      {:error {:message (str "Missing required fields: " (str/join ", " (map name missing)))
               :type "invalid_request_error"}})))

(defn reject-unknown [data allowed]
  (let [allowed-set (set allowed)
        extra (remove allowed-set (keys data))]
    (when (seq extra)
      {:error {:message (str "Unknown fields: " (str/join ", " (map name extra)))
               :type "invalid_request_error"}})))

(defn validate-fields
  "Runs each `[field predicate]` pair in `validators` (an entity-spec's
  `:validate` map, e.g. {:npi validation/valid-npi?}) against the
  pre-coercion request `data`. A field absent or blank is not validated
  here -- that is `require-fields`'s job -- so this only rejects a
  *present* value that fails its format predicate."
  [data validators]
  (let [invalid (keep (fn [[field valid?]]
                         (let [v (get data field)]
                           (when (and (some? v) (not= v "") (not (valid? v)))
                             field)))
                       validators)]
    (when (seq invalid)
      {:error {:message (str "Invalid fields: " (str/join ", " (map name invalid)))
               :type "invalid_request_error"}})))

;; --- list helpers ---
(defn apply-filters [rows params fields]
  (reduce (fn [out f]
            (let [want (get params f)]
              (if (and (some? want) (not= want ""))
                (filterv #(= (str (get % f)) (str want)) out)
                out)))
          rows fields))

(defn paginate [rows params]
  (let [limit (min (max (or (let [l (as-int (get params :limit))] (when (pos? l) l)) default-limit) 1) max-limit)
        start (get params :starting_after)
        rows (if (some? start)
               (let [ids (mapv :id rows) idx (.indexOf ^java.util.List ids start)]
                 #?(:clj (if (>= idx 0) (vec (drop (inc idx) rows)) rows)
                    :cljs (let [i (.indexOf (to-array (mapv :id rows)) start)] (if (>= i 0) (vec (drop (inc i) rows)) rows))))
               rows)
        page (vec (take limit rows))]
    [page (> (count rows) limit)]))

(defn expand [store rec params refs]
  (let [want (set (str/split (or (get params :expand) "") #","))]
    (reduce (fn [r [field ent]]
              (if (and (contains? want (name field)) (get r field))
                (assoc r (keyword (str (name field) "_obj")) (first (query store ent (get r field))))
                r))
            rec refs)))

;; --- generic handlers (return [body status]) ---
(defn- spec-for [entity] (first (filter #(= (:entity %) entity) entity-specs)))
(defn- not-found [] [{:error {:message "Not found" :type "not_found"}} 404])

(defn handle-create [store entity data]
  (let [{:keys [fields required coerce id-prefix validate]} (spec-for entity)]
    (or (some-> (reject-unknown data fields) (vector 400))
        (some-> (require-fields data required) (vector 400))
        (some-> (validate-fields data validate) (vector 400))
        (let [base {:id (new-id id-prefix)}
              rec (reduce (fn [m f] (assoc m f (coerce-field (get coerce f) (get data f)))) base fields)
              rec (assoc rec :createdAt (now) :updatedAt (now))]
          (persist! store entity rec)
          [rec 201]))))

(defn handle-list [store entity params]
  (let [{:keys [fields]} (spec-for entity)
        rows (apply-filters (query store entity) params fields)
        [page has-more] (paginate rows params)]
    [{:object "list" :data page :has_more has-more :count (count page) :total (count rows)} 200]))

(defn handle-get [store entity id params]
  (let [{:keys [refs]} (spec-for entity) rows (query store entity id)]
    (if (empty? rows) (not-found) [(expand store (first rows) params refs) 200])))

(defn handle-update [store entity id data]
  (let [{:keys [fields validate]} (spec-for entity) rows (query store entity id)]
    (if (empty? rows)
      (not-found)
      (or (some-> (reject-unknown data fields) (vector 400))
          (some-> (validate-fields data validate) (vector 400))
          (let [rec (reduce-kv (fn [m k v] (if (#{:id :createdAt} k) m (assoc m k v)))
                               (first rows) data)
                rec (assoc rec :updatedAt (now))]
            (persist! store entity rec)
            [rec 200])))))

(defn handle-delete [store entity id]
  (if (empty? (query store entity id)) (not-found) [(retract! store entity id) 200]))

(defn healthz [] [{:status "ok" :actor "athenahealth-compat" :tier tier :entities entities} 200])

;; --- WASM runtime registration (kotodama). The runtime host owns the live
;;     Datom log; handlers stay pure folds over a store, so this is G5-clean. ---
(defn start! [] :athenahealth-compat/ready)
