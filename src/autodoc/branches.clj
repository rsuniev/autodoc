(ns autodoc.branches
  (:use [clojure.contrib.duck-streams :only [reader]]
        [clojure.contrib.java-utils :only [file]]
        [clojure.contrib.pprint :only [cl-format pprint]]
        [clojure.contrib.pprint.utilities :only [prlabel]]
        [clojure.contrib.str-utils :only (re-split)]
        [clojure.contrib.shell-out :only [with-sh-dir sh]]
        [autodoc.params :only (params)]
        [autodoc.build-html :only (branch-subdir)]
        [autodoc.doc-files :only (xform-tree)])
  
  (import [java.io File]))

;;; stolen from lancet
(defn env [val]
  (System/getenv (name val)))

(defn- build-sh-args [args]
  (concat (re-split #"\s+" (first args)) (rest args)))

(defn system [& args]
  (pprint args)
  (println (apply sh (build-sh-args args))))

(defn switch-branches 
  "Switch to the specified branch"
  [branch]
  (with-sh-dir (params :root)
    (system "git fetch")
    (system (str "git checkout " branch))
    (system (str "git merge origin/" branch))))

(defn path-str [path-seq] 
  (apply str (interpose (System/getProperty "path.separator")
                        (map #(.getAbsolutePath (file %)) path-seq))))

(defn exec-clojure [class-path & args]
  (apply system (concat [ "java" "-cp"] 
                        [(path-str class-path)]
                        ["clojure.main" "-e"]
                        args)))

(defn expand-jar-path [jar-dirs]
  (apply concat 
         (for [jar-dir jar-dirs]
           (filter #(.endsWith (.getName %) ".jar")
                   (file-seq (java.io.File. jar-dir))))))
(defn do-collect 
  "Collect the namespace and var info for the checked out branch"
  []
  (let [class-path (concat 
                    (filter 
                     identity
                     [(or (params :built-clojure-jar)
                          (str (env :HOME) "/src/clj/clojure/clojure.jar"))
                      "src"
                      (.getPath (File. (params :root) (params :source-path)))
                      "."])
                    (params :load-classpath)
                    (expand-jar-path (params :load-jar-dirs)))
        tmp-file (File/createTempFile "collect-" ".clj")]
    (exec-clojure class-path 
                  (cl-format 
                   nil 
                   "(use 'autodoc.collect-info) (collect-info-to-file \"~a\" \"~a\")"
                   (params :param-dir)
                   (.getAbsolutePath tmp-file)))
    (try 
     (with-open [f (java.io.PushbackReader. (reader tmp-file))] 
       (binding [*in* f] (read)))
     (finally 
      (.delete tmp-file)))))

(defn do-build 
  "Execute an ant build in the given directory, if there's a build.xml"
  [dir branch]
  (when-let [build-file (first
                         (filter
                          #(.exists (file dir %))
                          [(str "build-" branch ".xml") "build.xml"]))]
    (with-sh-dir dir
      (system "ant"
              (str "-Dsrc-dir=" (params :root))
              (str "-Dclojure-jar=" (params :built-clojure-jar))
              "-buildfile" build-file))))

(defn with-first [s]
  (map #(vector %1 %2) s (conj (repeat false) true)))

(defn load-branch-data 
  "Collects the doc data from all the branches specified in the params and
 executes the function f for each branch with the collected data. When f is executed, 
 the correct branch will be checked out and any branch-specific parameters 
 will be bound. Takes a spec of [[branch-name parameter-overides] ... ] and 
 calls f as (f branch-name first? ns-info)."
  [branch-spec f]
  (doseq [[[branch-name param-overrides] first?] (with-first branch-spec)]
    (binding [params (merge params param-overrides)]
      (when branch-name (switch-branches branch-name))
      (do-build (params :param-dir) branch-name)
      (xform-tree (str (params :root) "/doc")
                  (str (params :output-path) "/"
                       (when-not first? (str (branch-subdir branch-name) "/"))
                       "doc"))
      (let [all-branch-names (seq (filter identity (map first branch-spec)))] 
        (f branch-name first? all-branch-names (doall (do-collect)))))))
