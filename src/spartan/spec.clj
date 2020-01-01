;;   This source contains code from
;;   https://github.com/clojure/spec.alpha/blob/master/src/main/clojure/clojure/spec/alpha.clj
;;   which is licensed as follows:

;;   Copyright (c) Rich Hickey. All rights reserved.
;;   The use and distribution terms for this software are covered by the
;;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;;   which can be found in the file epl-v10.html at the root of this distribution.
;;   By using this software in any fashion, you are agreeing to be bound by
;;   the terms of this license.
;;   You must not remove this notice, or any other, from this software.

(ns spartan.spec
  (:refer-clojure :exclude [+ * and assert or cat def keys merge])
  (:require [clojure.walk :as walk]))

;; 52
(defonce ^:private registry-ref (atom {}))

;; 54
(defn- deep-resolve [reg k]
  (loop [spec k]
    (if (ident? spec)
      (recur (get reg spec))
      spec)))

;; 60
(defn- reg-resolve
  "returns the spec/regex at end of alias chain starting with k, nil if not found, k if k not ident"
  [k]
  (if (ident? k)
    (let [reg @registry-ref
          spec (get reg k)]
      (if-not (ident? spec)
        spec
        (deep-resolve reg spec)))
    k))

;; 71
(defn- reg-resolve!
  "returns the spec/regex at end of alias chain starting with k, throws if not found, k if k not ident"
  [k]
  (if (ident? k)
    (clojure.core/or (reg-resolve k)
                     (throw (Exception. (str "Unable to resolve spec: " k))))
    k))

;; 79
(defn spec?
  "returns x if x is a spec object, else logical false"
  [x]
  (when (identical? ::spec (:type x))
    x))

;; 85
(defn regex?
  [x]
  (clojure.core/and (::op x) x))

;; 90
(defn- with-name [spec name]
  (cond
    (ident? spec) spec
    (regex? spec) (assoc spec ::name name)
    (instance? clojure.lang.IObj spec)
    (with-meta spec (assoc (meta spec) ::name name))
    :else spec))

;; 98
(defn- spec-name [spec]
  (cond
    (ident? spec) spec
    (regex? spec) (::name spec)
    (instance? clojure.lang.IObj spec)
    (-> (meta spec) ::name)))

;; 107
(declare spec-impl)
;; 108
(declare regex-spec-impl)

;; 110
(defn- maybe-spec
  "spec-or-k must be a spec, regex or resolvable kw/sym, else returns nil."
  [spec-or-k]
  (let [s (clojure.core/or (clojure.core/and (ident? spec-or-k) (reg-resolve spec-or-k))
                           (spec? spec-or-k)
                           (regex? spec-or-k)
                           nil)]
    (if (regex? s)
      (with-name s (spec-name s))
      s)))

;; 121
(defn- the-spec
  "spec-or-k must be a spec, regex or kw/sym, else returns nil. Throws if unresolvable kw/sym"
  [spec-or-k]
  (clojure.core/or
   (maybe-spec spec-or-k)
   (when (ident? spec-or-k)
     (throw (Exception. (str "Unable to resolve spec: " spec-or-k))))))

(defn specize*
  ([x] (specize* x nil))
  ([x form]
   (cond (keyword? x) (reg-resolve! x)
         (symbol? x) (reg-resolve! x)
         (set? x) (spec-impl form x)
         (regex? x) (assoc x :type ::spec)
         :else (if (clojure.core/and (not (map? x)) (ifn? x))
                 (if-let [s false
                          ;; TODO
                          #_(fn-sym o)]
                   (spec-impl s x)
                   (spec-impl ::unknown x))
                 (spec-impl ::unknown x)))))

;; 158
(defn- specize
  ([s] (clojure.core/or (spec? s) (specize* s)))
  ([s form] (clojure.core/or (spec? s) (specize* s form))))

;; 162
(defn invalid?
  "tests the validity of a conform return value"
  [ret]
  (identical? ::invalid ret))

(declare re-conform)

(defn conform* [spec x]
  (cond (regex? spec)
        (if (clojure.core/or (nil? x) (sequential? x))
          (re-conform spec (seq x))
          ::invalid)
        (:cform spec)
        ((:cform spec) x)
        :else
        (let [pred (:pred spec)
              ret (pred x)]
          (if ret x ::invalid))))

;; 167
(defn conform
  [spec x]
  (conform* (specize spec) x))

;; 173
;; unform: TODO

;; 180
;; form: TODO

;; 186
;; abbrev: TODO

;; 205:
;; describe: TODO

;; 210:
;; with-gen: TODO

;; 218:
;; explain-data*: TODO

;; 234:
;; explain-printer: TODO

;; 259:
;; *explain-out*: TODO

;; 267:
;; explain: TODO

;; 277
(declare valid?)

;; 279
;; gensub-: TODO

;; 292
;; gen: TODO

;; 305
(defn- ->sym
  "Returns a symbol from a symbol or var"
  [x]
  (if (var? x)
    (let [m (meta x)]
      (symbol (str (ns-name (:ns m))) (str (:name m))))
    x))

;; 314
(defn- unfn [expr]
  (if (clojure.core/and (seq? expr)
                        (symbol? (first expr))
                        (= "fn*" (name (first expr))))
    (let [[[s] & form] (rest expr)]
      (conj (walk/postwalk-replace {s '%} form) '[%] 'fn))
    expr))

;; 322
(defn- res [form]
  (cond
    (keyword? form) form
    (symbol? form) (clojure.core/or (-> form resolve ->sym) form)
    (sequential? form) (walk/postwalk #(if (symbol? %) (res %) %) (unfn form))
    :else form))

;; 329
(defn def-impl
  "Do not call this directly, use 'def'"
  [k form spec]
  (clojure.core/assert (clojure.core/and (ident? k) (namespace k)) "k must be namespaced keyword or resolvable symbol")
  (if (nil? spec)
    (swap! registry-ref dissoc k)
    (let [spec (if (clojure.core/or (spec? spec) (regex? spec) (get @registry-ref spec))
                 spec
                 (spec-impl form spec))]
      (swap! registry-ref assoc k (with-name spec k))))
  k)

;; 341
#_(defn- ns-qualify
    "Qualify symbol s by resolving it or using the current *ns*."
    [s]
    (if-let [ns-sym (some-> s namespace symbol)]
      (clojure.core/or (some-> (get (ns-aliases *ns*) ns-sym) str (symbol (name s)))
                       s)
      (symbol (str (.name *ns*)) (str s))))

;; 349
(defmacro def
  "Given a namespace-qualified keyword or resolvable symbol k, and a
  spec, spec-name, predicate or regex-op makes an entry in the
  registry mapping k to the spec. Use nil to remove an entry in
  the registry for k."
  [k spec-form]
  (let [k (if (symbol? k) (throw (Exception. "Only keywords allowed for now"))
              #_(ns-qualify k) k)]
    `(def-impl '~k '~(res spec-form) ~spec-form)))

;; 358
(defn registry
  "returns the registry map, prefer 'get-spec' to lookup a spec by name"
  []
  @registry-ref)

;; 363
(defn get-spec
  "Returns spec registered for keyword/symbol/var k, or nil."
  [k]
  (get (registry) (if (keyword? k) k (->sym k))))

;; 368
(defmacro spec
  [form & {:keys [gen]}]
  (when form
    `(spec-impl '~(res form) ~form ~gen nil)))

;; 387
;; multi-spec: TODO

;; 416
(defmacro keys
  [& {:keys [req req-un opt opt-un gen]}]
  (let [unk #(-> % name keyword)
        req-keys (filterv keyword? (flatten req))
        req-un-specs (filterv keyword? (flatten req-un))
        _ (clojure.core/assert (every? #(clojure.core/and (keyword? %) (namespace %)) (concat req-keys req-un-specs opt opt-un))
                               "all keys must be namespace-qualified keywords")
        req-specs (into req-keys req-un-specs)
        req-keys (into req-keys (map unk req-un-specs))
        opt-keys (into (vec opt) (map unk opt-un))
        opt-specs (into (vec opt) opt-un)
        gx (gensym)
        parse-req (fn [rk f]
                    (map (fn [x]
                           (if (keyword? x)
                             `(contains? ~gx ~(f x))
                             (walk/postwalk
                              (fn [y] (if (keyword? y) `(contains? ~gx ~(f y)) y))
                              x)))
                         rk))
        pred-exprs [`(map? ~gx)]
        pred-exprs (into pred-exprs (parse-req req identity))
        pred-exprs (into pred-exprs (parse-req req-un unk))
        keys-pred `(fn* [~gx] (c/and ~@pred-exprs))
        pred-exprs (mapv (fn [e] `(fn* [~gx] ~e)) pred-exprs)
        pred-forms (walk/postwalk res pred-exprs)]
    ;; `(map-spec-impl ~req-keys '~req ~opt '~pred-forms ~pred-exprs ~gen)
    `(map-spec-impl {:req '~req :opt '~opt :req-un '~req-un :opt-un '~opt-un
                     :req-keys '~req-keys :req-specs '~req-specs
                     :opt-keys '~opt-keys :opt-specs '~opt-specs
                     :pred-forms '~pred-forms
                     :pred-exprs ~pred-exprs
                     :keys-pred ~keys-pred
                     :gfn ~gen})))

;; 478
(defmacro or
  "Takes key+pred pairs, e.g.
  (s/or :even even? :small #(< % 42))
  Returns a destructuring spec that returns a map entry containing the
  key of the first matching pred and the corresponding value. Thus the
  'key' and 'val' functions can be used to refer generically to the
  components of the tagged return."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (clojure.core/assert (clojure.core/and (even? (count key-pred-forms)) (every? keyword? keys)) "spec/or expects k1 p1 k2 p2..., where ks are keywords")
    `(or-spec-impl ~keys '~pf ~pred-forms)))

;; 495
(defmacro and
  "Takes predicate/spec-forms, e.g.
  (s/and even? #(< % 42))
  Returns a spec that returns the conformed value. Successive
  conformed values propagate through rest of predicates."
  [& pred-forms]
  `(and-spec-impl '~(mapv res pred-forms) ~(vec pred-forms) nil))

;; 505
(defmacro merge
  "Takes map-validating specs (e.g. 'keys' specs) and
  returns a spec that returns a conformed map satisfying all of the
  specs.  Unlike 'and', merge can generate maps satisfying the
  union of the predicates."
  [& pred-forms]
  `(merge-spec-impl '~(mapv res pred-forms) ~(vec pred-forms) nil))

;; 513
(defn- res-kind
  [opts]
  (let [{kind :kind :as mopts} opts]
    (->>
     (if kind
       (assoc mopts :kind `~(res kind))
       mopts)
     (mapcat identity))))

;; 522
(defmacro every
  [pred & {:keys [into kind count max-count min-count distinct gen-max gen] :as opts}]
  (let [desc (::describe opts)
        nopts (-> opts
                  (dissoc :gen ::describe)
                  (assoc ::kind-form `'~(res (:kind opts))
                         ::describe (clojure.core/or desc `'(every ~(res pred) ~@(res-kind opts)))))
        gx (gensym)
        cpreds (cond-> [(list (clojure.core/or kind `coll?) gx)]
                 count (conj `(= ~count (bounded-count ~count ~gx)))

                 (clojure.core/or min-count max-count)
                 (conj `(<= (c/or ~min-count 0)
                            (bounded-count (if ~max-count (inc ~max-count) ~min-count) ~gx)
                            (c/or ~max-count Integer/MAX_VALUE)))

                 distinct
                 (conj `(c/or (empty? ~gx) (apply distinct? ~gx))))]
    `(every-impl '~pred ~pred ~(assoc nopts ::cpred `(fn* [~gx] (c/and ~@cpreds))) ~gen)))

;; 570
(defmacro every-kv
  "like 'every' but takes separate key and val preds and works on associative collections.
  Same options as 'every', :into defaults to {}
  See also - map-of"

  [kpred vpred & opts]
  (let [desc `(every-kv ~(res kpred) ~(res vpred) ~@(res-kind opts))]
    `(every (tuple ~kpred ~vpred) ::kfn (fn [i# v#] (nth v# 0)) :into {} ::describe '~desc ~@opts)))

;; 581
(defmacro coll-of
  "Returns a spec for a collection of items satisfying pred. Unlike
  'every', coll-of will exhaustively conform every value.
  Same options as 'every'. conform will produce a collection
  corresponding to :into if supplied, else will match the input collection,
  avoiding rebuilding when possible.
  See also - every, map-of"
  [pred & opts]
  (let [desc `(coll-of ~(res pred) ~@(res-kind opts))]
    `(every ~pred ::conform-all true ::describe '~desc ~@opts)))

;; 594
(defmacro map-of
  "Returns a spec for a map whose keys satisfy kpred and vals satisfy
  vpred. Unlike 'every-kv', map-of will exhaustively conform every
  value.
  Same options as 'every', :kind defaults to map?, with the addition of:
  :conform-keys - conform keys as well as values (default false)
  See also - every-kv"
  [kpred vpred & opts]
  (let [desc `(map-of ~(res kpred) ~(res vpred) ~@(res-kind opts))]
    `(every-kv ~kpred ~vpred ::conform-all true :kind map? ::describe '~desc ~@opts)))

;; 609
(defmacro *
  "Returns a regex op that matches zero or more values matching
  pred. Produces a vector of matches iff there is at least one match"
  [pred-form]
  `(rep-impl '~(res pred-form) ~pred-form))

;; 615
(defmacro +
  "Returns a regex op that matches one or more values matching
  pred. Produces a vector of matches"
  [pred-form]
  `(rep+impl '~(res pred-form) ~pred-form))

;; 621
(defmacro ?
  "Returns a regex op that matches zero or one value matching
  pred. Produces a single value (not a collection) if matched."
  [pred-form]
  `(maybe-impl ~pred-form '~(res pred-form)))

;; 627
(defmacro alt
  "Takes key+pred pairs, e.g.
  (s/alt :even even? :small #(< % 42))
  Returns a regex op that returns a map entry containing the key of the
  first matching pred and the corresponding value. Thus the
  'key' and 'val' functions can be used to refer generically to the
  components of the tagged return"
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (clojure.core/assert (clojure.core/and (even? (count key-pred-forms)) (every? keyword? keys)) "alt expects k1 p1 k2 p2..., where ks are keywords")
    `(alt-impl ~keys ~pred-forms '~pf)))

;; 644
(defmacro cat
  "Takes key+pred pairs, e.g.
  (s/cat :e even? :o odd?)
  Returns a regex op that matches (all) values in sequence, returning a map
  containing the keys of each pred and the corresponding value."
  [& key-pred-forms]
  (let [pairs (partition 2 key-pred-forms)
        keys (mapv first pairs)
        pred-forms (mapv second pairs)
        pf (mapv res pred-forms)]
    (clojure.core/assert (clojure.core/and (even? (count key-pred-forms)) (every? keyword? keys)) "cat expects k1 p1 k2 p2..., where ks are keywords")
    `(cat-impl ~keys ~pred-forms '~pf)))

;; 660
(defmacro &
  "takes a regex op re, and predicates. Returns a regex-op that consumes
  input as per re but subjects the resulting value to the
  conjunction of the predicates, and any conforming they might perform."
  [re & preds]
  (let [pv (vec preds)]
    `(amp-impl ~re '~(res re) ~pv '~(mapv res pv))))

;; 668
(defmacro conformer
  "takes a predicate function with the semantics of conform i.e. it should return either a
  (possibly converted) value or :clojure.spec.alpha/invalid, and returns a
  spec that uses it as a predicate/conformer. Optionally takes a
  second fn that does unform of result of first"
  ([f] `(spec-impl '(conformer ~(res f)) ~f nil true))
  ([f unf] `(spec-impl '(conformer ~(res f) ~(res unf)) ~f nil true ~unf)))

;; 676
;; fspec: TODO

;; 696
(defmacro tuple
  "takes one or more preds and returns a spec for a tuple, a vector
  where each element conforms to the corresponding pred. Each element
  will be referred to in paths using its ordinal."
  [& preds]
  (clojure.core/assert (seq preds))
  `(tuple-impl '~(mapv res preds) ~(vec preds)))

;; 704:
;; macroexpand-check: TODO

;; 716:
;; fdef: TODO

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; impl ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; 759
(defn- dt
  ([pred x form] (dt pred x form nil))
  ([pred x form cpred?]
   (if pred
     (if-let [spec (the-spec pred)]
       (conform spec x)
       (if (ifn? pred)
         (if cpred?
           (pred x)
           (if (pred x) x ::invalid))
         (throw (Exception. (str (pr-str form) " is not a fn, expected predicate fn")))))
     x)))

;; 772
(defn valid?
  "Helper function that returns true when x is valid for spec."
  ([spec x]
   (let [spec (specize spec)]
     (not (invalid? (conform* spec x)))))
  ([spec x form]
   (let [spec (specize spec form)]
     (not (invalid? (conform* spec x))))))

;; 781
(defn- pvalid?
  "internal helper function that returns true when x is valid for spec."
  ([pred x]
   (not (invalid? (dt pred x ::unknown))))
  ([pred x form]
   (not (invalid? (dt pred x form)))))

;; 788
;; explain-1: TODO

;; 915
(defn spec-impl [form pred]
  {:type ::spec
   :form form
   :pred pred})

;; 998
;; tuple-impl: TODO

;; 1060
(defn- tagged-ret [tag ret]
  ;; TODO: add clojure.lang.MapEntry to bb
  #_(clojure.lang.MapEntry. tag ret)
  [tag ret])


;; 1063
(defn or-spec-impl
  [keys forms preds]
  (let [id (java.util.UUID/randomUUID)
        kps (zipmap keys preds)
        specs (delay (mapv specize preds forms))
        cform (case (count preds)
                2 (fn [x]
                    (let [ret  (let [specs @specs
                                    ret (conform* (specs 0) x)]
                                (if (invalid? ret)
                                  (let [ret (conform* (specs 1) x)]
                                    (if (invalid? ret)
                                      ::invalid
                                      (tagged-ret (keys 1) ret)))
                                  (tagged-ret (keys 0) ret)))]
                      ret))
                3 (fn [x]
                    (let [specs @specs
                          ret (conform* (specs 0) x)]
                      (if (invalid? ret)
                        (let [ret (conform* (specs 1) x)]
                          (if (invalid? ret)
                            (let [ret (conform* (specs 2) x)]
                              (if (invalid? ret)
                                ::invalid
                                (tagged-ret (keys 2) ret)))
                            (tagged-ret (keys 1) ret)))
                        (tagged-ret (keys 0) ret))))
                (fn [x]
                  (let [specs @specs]
                    (loop [i 0]
                      (if (< i (count specs))
                        (let [spec (specs i)]
                          (let [ret (conform* spec x)]
                            (if (invalid? ret)
                              (recur (inc i))
                              (tagged-ret (keys i) ret))))
                        ::invalid)))))]
    {:type ::spec
     :id id
     :cform cform}))

;; 1130
(defn- and-preds [x preds forms]
  (loop [ret x
         [pred & preds] preds
         [form & forms] forms]
    (if pred
      (let [nret (dt pred ret form)]
        (if (invalid? nret)
          ::invalid
          ;;propagate conformed values
          (recur nret preds forms)))
      ret)))

;; 1153
;; and-spec-impl: TODO


;; 1197
;; merge-spec-impl: TODO

;; 1382
(defn- accept [x] {::op ::accept :ret x})

;; 1384
(defn- accept? [{:keys [::op]}]
  (= ::accept op))

;; 1387
(defn- pcat* [{[p1 & pr :as ps] :ps,  [k1 & kr :as ks] :ks, [f1 & fr :as forms] :forms, ret :ret, rep+ :rep+}]
  (when (every? identity ps)
    (if (accept? p1)
      (let [rp (:ret p1)
            ret (conj ret (if ks {k1 rp} rp))]
        (if pr
          (pcat* {:ps pr :ks kr :forms fr :ret ret})
          (accept ret)))
      {::op ::pcat, :ps ps, :ret ret, :ks ks, :forms forms :rep+ rep+})))

;; 1379
(defn- pcat [& ps] (pcat* {:ps ps :ret []}))

;; 1399
(defn cat-impl
  "Do not call this directly, use 'cat'"
  [ks ps forms]
  (pcat* {:ks ks, :ps ps, :forms forms, :ret {}}))

;; 1404
(defn- rep* [p1 p2 ret splice form]
  (when p1
    (let [r {::op ::rep, :p2 p2, :splice splice, :forms form :id (java.util.UUID/randomUUID)}]
      (if (accept? p1)
        (assoc r :p1 p2 :ret (conj ret (:ret p1)))
        (assoc r :p1 p1, :ret ret)))))

;; 1411
(defn rep-impl
  [form p] (rep* p p [] false form))

;; 1415
(defn rep+impl
  [form p]
  (pcat* {:ps [p (rep* p p [] true form)] :forms `[~form (* ~form)] :ret [] :rep+ form}))

;; 1420
(defn amp-impl
  [re re-form preds pred-forms]
  {::op ::amp :p1 re :amp re-form :ps preds :forms pred-forms})

;; 1425
(defn- filter-alt [ps ks forms f]
  (if (clojure.core/or ks forms)
    (let [pks (->> (map vector ps
                        (clojure.core/or (seq ks) (repeat nil))
                        (clojure.core/or (seq forms) (repeat nil)))
                   (filter #(-> % first f)))]
      [(seq (map first pks)) (when ks (seq (map second pks))) (when forms (seq (map #(nth % 2) pks)))])
    [(seq (filter f ps)) ks forms]))

;; 1434
(defn- alt* [ps ks forms]
  (let [[[p1 & pr :as ps] [k1 :as ks] forms] (filter-alt ps ks forms identity)]
    (when ps
      (let [ret {::op ::alt, :ps ps, :ks ks :forms forms}]
        (if (nil? pr)
          (if k1
            (if (accept? p1)
              (accept (tagged-ret k1 (:ret p1)))
              ret)
            p1)
          ret)))))

;; 1446
(defn- alts [& ps] (alt* ps nil nil))
;; 1447
(defn- alt2 [p1 p2] (if (clojure.core/and p1 p2) (alts p1 p2) (clojure.core/or p1 p2)))

;; 1449
(defn alt-impl
  "Do not call this directly, use 'alt'"
  [ks ps forms] (assoc (alt* ps ks forms) :id (java.util.UUID/randomUUID)))

;; 1453
(defn maybe-impl
  [p form] (assoc (alt* [p (accept ::nil)] nil [form ::nil]) :maybe form))

;; 1457
(defn- noret? [p1 pret]
  (clojure.core/or (= pret ::nil)
                   (clojure.core/and (#{::rep ::pcat} (::op (reg-resolve! p1))) ;;hrm, shouldn't know these
                                     (empty? pret))
                   nil))

;; 1463
(declare preturn)

;; 1465
(defn- accept-nil? [p]
  (let [{:keys [::op ps p1 p2 forms] :as p} (reg-resolve! p)]
    (case op
      ::accept true
      nil nil
      ::amp (clojure.core/and (accept-nil? p1)
                              (let [ret (-> (preturn p1) (and-preds ps (next forms)))]
                                (not (invalid? ret))))
      ::rep (clojure.core/or (identical? p1 p2) (accept-nil? p1))
      ::pcat (every? accept-nil? ps)
      ::alt (clojure.core/some accept-nil? ps))))

;; 1477
(declare add-ret)

(defn- and-preds [x preds forms]
  (loop [ret x
         [pred & preds] preds
         [form & forms] forms]
    (if pred
      (let [nret (dt pred ret form)]
        (if (invalid? nret)
          ::invalid
          ;;propagate conformed values
          (recur nret preds forms)))
      ret)))

;; 1479
(defn- preturn [p]
  (let [{[p0 & pr :as ps] :ps, [k :as ks] :ks, :keys [::op p1 ret forms] :as p} (reg-resolve! p)]
    (case op
      ::accept ret
      nil nil
      ::amp (let [pret (preturn p1)]
              (if (noret? p1 pret)
                ::nil
                (and-preds pret ps forms)))
      ::rep (add-ret p1 ret k)
      ::pcat (add-ret p0 ret k)
      ::alt (let [[[p0] [k0]] (filter-alt ps ks forms accept-nil?)
                  r (if (nil? p0) ::nil (preturn p0))]
              (if k0 (tagged-ret k0 r) r)))))

;; 1515
(defn- add-ret [p r k]
  (let [{:keys [::op ps splice] :as p} (reg-resolve! p)
        prop #(let [ret (preturn p)]
                (if (empty? ret) r ((if splice into conj) r (if k {k ret} ret))))]
    ;; TODO: revert when sci has support for lists in case
    #_(case op
        nil r
        (::alt ::accept ::amp)
        (let [ret (preturn p)]
          ;;(prn {:ret ret})
          (if (= ret ::nil) r (conj r (if k {k ret} ret))))

        (::rep ::pcat) (prop))
    (cond ;; op
      (nil? op) r
      (contains? #{::alt ::accept ::amp} op)
      (let [ret (preturn p)]
        ;;(prn {:ret ret})
        (if (= ret ::nil) r (conj r (if k {k ret} ret))))
      (contains? #{::rep ::pcat} op) (prop)
      :else (throw (Exception. (str "No matching case: " op))))))

;; 1528
(defn- deriv
  [p x]
  (let [{[p0 & pr :as ps] :ps, [k0 & kr :as ks] :ks, :keys [::op p1 p2 ret splice forms amp] :as p} (reg-resolve! p)]
    (when p
      (case op
        ::accept nil
        nil (let [ret (dt p x p)]
              (when-not (invalid? ret) (accept ret)))
        ::amp (when-let [p1 (deriv p1 x)]
                (if (= ::accept (::op p1))
                  (let [ret (-> (preturn p1) (and-preds ps (next forms)))]
                    (when-not (invalid? ret)
                      (accept ret)))
                  (amp-impl p1 amp ps forms)))
        ::pcat (alt2 (pcat* {:ps (cons (deriv p0 x) pr), :ks ks, :forms forms, :ret ret})
                     (when (accept-nil? p0) (deriv (pcat* {:ps pr, :ks kr, :forms (next forms), :ret (add-ret p0 ret k0)}) x)))
        ::alt (alt* (map #(deriv % x) ps) ks forms)
        ::rep (alt2 (rep* (deriv p1 x) p2 ret splice forms)
                    (when (accept-nil? p1) (deriv (rep* p2 p2 (add-ret p1 ret nil) splice forms) x)))))))

;; 1660
(defn- re-conform [p [x & xs :as data]]
  ;;(prn {:p p :x x :xs xs})
  (if (empty? data)
    (if (accept-nil? p)
      (let [ret (preturn p)]
        (if (= ret ::nil)
          nil
          ret))
      ::invalid)
    (if-let [dp (deriv p x)]
      (recur dp xs)
      ::invalid)))
