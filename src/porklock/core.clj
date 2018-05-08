(ns porklock.core
  (:gen-class)
  (:use [porklock.commands]
        [porklock.validation]
        [slingshot.slingshot :only [try+ throw+]])
  (:require [clojure.tools.cli :as cli]
            [clojure.string :as string]
            [common-cli.version :as version]
            [porklock.vault :as pork-vault]
            [clojure-commons.error-codes
             :as error
             :refer [ERR_DOES_NOT_EXIST ERR_NOT_A_FILE ERR_NOT_A_FOLDER ERR_NOT_WRITEABLE]]))


(defn- fmeta-split
  [arg]
  (remove nil? (string/split arg #",")))

(defn get-settings
  [args]
  (let [file-metadata (atom [])
        fmeta-set     #(reset! file-metadata (conj @file-metadata (fmeta-split %)))]
    (cli/cli
      args
      ["-u"
       "--user"
       "The user the tool should run as."
       :default nil]

      ["-z"
       "--debug-config"
       "The path to a local iRODS config. Used for debugging purposes only."
       :default nil]

      ["-s"
       "--source"
       "A path in iRODS to be downloaded."
       :default nil]

      ["-l"
       "--source-list"
       "A path to a local Path-List file, containing a list of paths in iRODS to be downloaded."
       :default nil]

      ["-d"
       "--destination"
       "The local directory that the files will be downloaded into."
       :default "."]

      ["-m"
       "--meta"
       "Comma-delimited ATTR-VALUE-UNIT"
       :parse-fn fmeta-set]

      ["-h"
       "--help"
       "Prints this help."
       :flag true])))

(defn put-settings
  [args]
  (let [file-metadata (atom [])
        fmeta-set     #(reset! file-metadata (conj @file-metadata (fmeta-split %)))]
    (cli/cli
      args
      ["-u"
       "--user"
       "The user the tool should run as."
       :default nil]

      ["-z"
       "--debug-config"
       "The path to a local iRODS config. Used for debugging purposes only."
       :default nil]

      ["-e"
       "--exclude"
       "The path to a file containing a list of paths to be excluded from uploads."
       :default ""]

      ["-x"
       "--exclude-delimiter"
       "Delimiter for the list of files to be excluded from uploads"
       :default "\n"]

      ["-i"
       "--include"
       "List of files to make sure are uploaded"
       :default ""]

      ["-n"
       "--include-delimiter"
       "Delimiter for the list of files that should be included in uploads."
       :default ","]

      ["-s"
       "--source"
       "The local directory containing files to be transferred."
       :default "."]

      ["-d"
       "--destination"
       "The destination directory in iRODS."
       :default nil]

      ["-m"
       "--meta"
       "Comma-delimited ATTR-VALUE-UNIT"
       :parse-fn fmeta-set]

      ["-p"
       "--skip-parent-meta"
       "Tells porklock to skip applying metadata to the parent directories of an analysis."
       :flag true]

      ["-h"
       "--help"
       "Prints this help."
       :flag true])))

(defn- vault-settings
  "Reads the VAULT_TOKEN, VAULT_ADDR, and JOB_UUID environment variables and
   places them into the options map as :vault-token, :vault-addr, and :job-uuid,
   respectively. This function does not check if the settings are empty before
   adding them to the map, that should be done elsewhere."
  [options]
  (assoc options :vault-token (System/getenv "VAULT_TOKEN")
                 :vault-addr  (System/getenv "VAULT_ADDR")
                 :job-uuid    (System/getenv "JOB_UUID")))

(defn- read-vault-config
  "Reads the iRODS config from Vault and puts it into the options map as a
   string value with a key of :config."
  [options]
  (if (:debug-config options)
    (assoc options :config (slurp (:debug-config options)))
    (assoc options :config (pork-vault/irods-config (:vault-addr options)
                                                    (:vault-token options)
                                                    (:job-uuid options)))))

(def usage "Usage: porklock get|put [options]")

(defn command
  [all-args]
  (when-not (pos? (count all-args))
    (println usage)
    (System/exit 1))

  (when-not (contains? #{"put" "get" "--version"} (first all-args))
    (println usage)
    (System/exit 1))

  (string/trim (first all-args)))

(defn settings
  [cmd cmd-args]
  (try
    (case cmd
      "get"   (get-settings cmd-args)
      "put"   (put-settings cmd-args)
      "--version" []
      (do
        (println usage)
        (System/exit 1)))
    (catch Exception e
      (println (.getMessage e))
      (System/exit 1))))

(defn err-msg
  [err]
  (let [err-code (:error_code err)]
    (cond
      (= err-code ERR_DOES_NOT_EXIST)      (str "Path does not exist: " (:path err))
      (= err-code ERR_NOT_A_FOLDER)        (str "Path is not a folder: " (:path err))
      (= err-code ERR_NOT_A_FILE)          (str "Path is not a file: " (:path err))
      (= err-code ERR_NOT_WRITEABLE)       (str "Client needs write permission on: " (:path err))
      (= err-code "ERR_PATH_NOT_ABSOLUTE") (str "Path is not absolute: " (:path err))
      (= err-code "ERR_BAD_EXIT_CODE")     (str "Command exited with status: " (:exit-code err))
      (= err-code "ERR_ACCESS_DENIED")     "You can't run this."
      (= err-code "ERR_MISSING_OPTION")    (str "Missing required option: " (:option err))
      :else                                (str "Error: " err))))


(defn -main
  [& args]
  (try+
    (let [cmd          (command args)
          version-info (version/version-info "org.cyverse" "porklock")
          cmd-args     (rest args)
          [options remnants banner] (settings cmd cmd-args)
          options      (vault-settings options)]

      (when (= cmd "--version")
        (println version-info)
        (System/exit 0))

      (println "[porklock] [arguments] " args)
      (println (str "[porklock] [command] '" cmd "'"))
      (println "[porklock] [options] " options)
      (println "[porklock] [remnants] " remnants)

      (when-not (pos? (count cmd-args))
        (println banner)
        (System/exit 1))

      (when (:help options)
        (println banner)
        (System/exit 0))

      (case cmd
        "get"   (do
                  (validate-get options)
                  (iget-command (read-vault-config options))
                  (System/exit 0))

        "put"   (do
                  (validate-put options)
                  (iput-command (read-vault-config options))
                  (System/exit 0))

        (do
          (println banner)
          (System/exit 1))))
    (catch error/error? err
      (println (err-msg err))
      (System/exit 1))
    (catch Exception e
      (println (error/format-exception e))
      (System/exit 2))))
