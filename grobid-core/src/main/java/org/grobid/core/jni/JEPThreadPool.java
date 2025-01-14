package org.grobid.core.jni;

import java.util.concurrent.*;
import java.util.*;
import java.io.*;
import java.nio.file.Path;

import org.grobid.core.utilities.GrobidProperties;
import org.grobid.core.exceptions.GrobidResourceException;

import jep.Jep;
import jep.JepConfig;
import jep.JepException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * For using DeLFT deep learning models, we use JEP as JNI CPython interpreter.
 * JEP presents the following constraint: A thread that creates a JEP instance
 * must be reused for all method calls to that JEP instance. For ensuring this,
 * we pool the Jep instances in a singleton class.
 */

public class JEPThreadPool {
    private static final Logger LOGGER = LoggerFactory.getLogger(JEPThreadPool.class);

    private int POOL_SIZE = 1;

    private ExecutorService executor;
    private Map<Long, Jep> jepInstances;

    private static volatile JEPThreadPool instance;

    public static JEPThreadPool getInstance() {
        if (instance == null) {
            getNewInstance();
        }
        return instance;
    }

    /**
     * Creates a new instance.
     */
    private static synchronized void getNewInstance() {
        LOGGER.debug("Get new instance of JEPThreadPool");
        instance = new JEPThreadPool();
    }

    /**
     * Hidden constructor
     */
    private JEPThreadPool() {
        // creating a pool of POOL_SIZE threads
        //executor = Executors.newFixedThreadPool(POOL_SIZE); 
        executor = Executors.newSingleThreadExecutor();
        // each of these threads is associated to a JEP instance
        jepInstances = new ConcurrentHashMap<>();
    }

    private File getAndValidateDelftPath() {
        File delftPath = new File(GrobidProperties.getDeLFTFilePath());
        if (!delftPath.exists()) {
            throw new GrobidResourceException("DeLFT installation path does not exist");
        }
        if (!delftPath.isDirectory()) {
            throw new GrobidResourceException("DeLFT installation path is not a directory");
        }
        return delftPath;
    }

    private JepConfig getJepConfig(File delftPath, Path sitePackagesPath) {
        JepConfig config = new JepConfig();
        config.addIncludePaths(delftPath.getAbsolutePath());
        //config.setRedirectOutputStreams(GrobidProperties.isDeLFTRedirectOutput());
        config.setRedirectOutputStreams(true);
        if (sitePackagesPath != null) {
            config.addIncludePaths(sitePackagesPath.toString());
        }
        config.setClassLoader(Thread.currentThread().getContextClassLoader());
        return config;
    }

    private void initializeJepInstance(Jep jep, File delftPath) throws JepException {
        // import packages
        jep.eval("import os");
        jep.eval("import numpy as np");
        jep.eval("import keras.backend as K");
        jep.eval("os.chdir('" + delftPath.getAbsolutePath() + "')");
        String delftPackage = GrobidProperties.getDeLFTPackage();
        LOGGER.debug("delft package: {}", delftPackage);
        jep.eval("from " + delftPackage + ".utilities.Embeddings import Embeddings");
        jep.eval("import " + delftPackage + ".sequenceLabelling");
        jep.eval("from " + delftPackage + ".sequenceLabelling import Sequence");
        jep.eval("from " + delftPackage + ".sequenceLabelling.reader import load_data_and_labels_crf_file");
        jep.eval("from " + delftPackage + ".sequenceLabelling.reader import load_data_crf_string");
        jep.eval("from sklearn.model_selection import train_test_split");
    }

    private Jep createJEPInstance() {
        Jep jep = null;
        boolean success = false;
        try {
            File delftPath = this.getAndValidateDelftPath();
            JepConfig config = this.getJepConfig(
                delftPath,
                PythonEnvironmentConfig.getInstance().getSitePackagesPath()
            );
            jep = new Jep(config);
            this.initializeJepInstance(jep, delftPath);
            success = true;
            return jep;
        } catch(JepException e) {
            LOGGER.error("JEP initialization failed", e);
            throw new RuntimeException("JEP initialization failed", e);
        } catch(GrobidResourceException e) {
            LOGGER.error("DeLFT installation path invalid, JEP initialization failed", e);
            throw new RuntimeException("DeLFT installation path invalid, JEP initialization failed", e);
        } finally {
            if (!success && (jep != null)) {
                try {
                    jep.close();
                } catch (JepException e) {
                    LOGGER.error("failed to close JEP instance", e);
                }
            }
        }
    }

    /**
     * To be called by the thread executing python commands via JEP.
     * The method will return to the thread its dedicated Jep instance
     * (or create one the first time).
     */
    public synchronized Jep getJEPInstance() {
        long threadId = Thread.currentThread().getId();
        Jep jep = jepInstances.get(threadId);
        if (jep == null) {
            LOGGER.info("Creating JEP instance for thread " + threadId);
            jep = this.createJEPInstance();
            jepInstances.put(threadId, jep);
        }
        try {
            jep.isValidThread();
        } catch (JepException e) {
            LOGGER.warn("JEP instance no longer usable, creating new instance", e);
            jep = this.createJEPInstance();
            jepInstances.put(threadId, jep);
        }
        return jep;
    }

    public void run(Runnable task) throws InterruptedException {
        System.out.println("running thread: " + Thread.currentThread().getId());
        Future future = executor.submit(task);
        // wait until done (in ms)
        while (!future.isDone()) {
            Thread.sleep(1);
        }
    }

    public String call(Callable<String> task) throws InterruptedException, ExecutionException {
        Future<String> future = executor.submit(task);
        // block until done
        return future.get();
    }

}