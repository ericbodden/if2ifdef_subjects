/*-
 * See the file LICENSE for redistribution information.
 *
 * Copyright (c) 2002, 2013 Oracle and/or its affiliates.  All rights reserved.
 *
 */

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOError;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.text.DateFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.CheckpointConfig;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Durability;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.Transaction;
import com.sleepycat.je.log.FileManager;
import com.sleepycat.je.util.DbVerify;
import com.sleepycat.je.util.DbVerifyLog;
import com.sleepycat.je.utilint.TracerFormatter;

/**
 * A stress test to simulate IO errors (exceptions thrown by RandomAccessFile)
 * to ensure log corruption does not occur.
 */
public class IOErrorStress {

    private static final String EXCEPTION_MSG = "Generated by IOErrorStress";
    private static final DateFormat DATE_FORMAT =
        TracerFormatter.makeDateFormat();
    private static final String DB_NAME = "foo";
    private static final int DEFAULT_LOAD_THREADS = 10;
    private static final int DEFAULT_CLEANER_THREADS = 2;
    private static final int DEFAULT_DURATION_MINUTES = 15;
    private static final int DEFAULT_CACHE_MB = 10;
    private static final String DEFAULT_HOME_DIR = "tmp";

    public static void main(final String[] args) {
        try {
            printArgs(args);
            final IOErrorStress test = new IOErrorStress(args);
            test.runTest();
            System.exit(0);
        } catch (Throwable e) {
            e.printStackTrace(System.out);
            System.exit(-1);
        }
    }

    private Environment env;
    private Database db;
    private String homeDir = DEFAULT_HOME_DIR;
    private int nLoadThreads = DEFAULT_LOAD_THREADS;
    private int nCleanerThreads = DEFAULT_CLEANER_THREADS;
    private int durationMinutes = DEFAULT_DURATION_MINUTES;
    private int cacheMb = DEFAULT_CACHE_MB;
    private volatile boolean injectErrors = false;
    private final Random[] loadRandoms;

    private IOErrorStress(String[] args) {

        /* Parse arguments. */
        for (int i = 0; i < args.length; i += 1) {
            final String arg = args[i];
            final boolean moreArgs = i < args.length - 1;
            if (arg.equals("-h") && moreArgs) {
                homeDir = args[++i];
            } else if (arg.equals("-threads") && moreArgs) {
                nLoadThreads = Integer.parseInt(args[++i]);
            } else if (arg.equals("-cleaners") && moreArgs) {
                nCleanerThreads = Integer.parseInt(args[++i]);
            } else if (arg.equals("-minutes") && moreArgs) {
                durationMinutes = Integer.parseInt(args[++i]);
            } else if (arg.equals("-cacheMB") && moreArgs) {
                cacheMb = Integer.parseInt(args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown arg: " + arg);
            }
        }

        /* Initialize Random generators for load threads. */
        loadRandoms = new Random[nLoadThreads];
        for (int i = 0; i < nLoadThreads; i += 1) {
            loadRandoms[i] = new Random(i * 1000);
        }

        /* Set factory for generating IO errors.  Is initially disabled. */
        FileManager.fileFactory = new FileManager.FileFactory() {

            @Override
            public RandomAccessFile createFile(final File envHome,
                                               final String name,
                                               final String mode)
                throws FileNotFoundException {

                return new ErrorInjector(name, mode);
            }
        };
    }
    
    private static void printArgs(String[] args) {
        System.out.print("\nCommand line arguments:");
        for (String arg : args) {
            System.out.print(' ');
            System.out.print(arg);
        }
        System.out.println();
    }

    private void runTest()
        throws Throwable {

        final long durationMs = durationMinutes * 60 * 1000;
        final long endTime = System.currentTimeMillis() + durationMs;

        injectErrors = true;
        while (System.currentTimeMillis() < endTime) {
            try {
                log("Open environment");
                openEnv();
                log("Verify logs...");
                try {
                    final DbVerifyLog verifier = new DbVerifyLog(env);
                    verifier.verifyAll();
                } catch (final Throwable e) {
                    log("***DbVerifyLog failed!");
                    throw e;
                }
                log("Verify database...");
                injectErrors = false;
                try {
                    final DbVerify verifier =
                        new DbVerify(env, DB_NAME, false /*quiet*/);
                    if (!verifier.verify(System.out)) {
                        throw new IllegalStateException
                            ("***DbVerify failed: result is false!");
                    }
                } catch (final Throwable e) {
                    log("***DbVerify failed: see exception!");
                    throw e;
                } finally {
                    injectErrors = true;
                }
                openDb();
                log("Run operations...");
                if (runLoadThreads()) {
                    throw new IllegalStateException
                        ("***Load threads failed: see exception(s)!");
                }
                closeDbAndEnv();
            } catch (final Throwable e) {
                if (!isGeneratedException(e)) {
                    log("***Unexpected exception!");
                    throw e;
                }
                log("Generated exception, continuing...");
                e.printStackTrace(System.out);
                closeDbAndEnv();
            }
        }

        log("Test succeeded: no errors after " + durationMinutes + " minutes");
    }

    private boolean runLoadThreads() {

        final AtomicBoolean unexpectedEx = new AtomicBoolean(false);
        final int nThreads = loadRandoms[0].nextInt(nLoadThreads) + 1;
        final Thread[] threads = new Thread[nThreads];

        for (int i = 0; i < nThreads; i += 1) {
            final Random rnd = loadRandoms[i];
            threads[i] = new Thread() {
                @Override
                public void run() {
                    if (runLoad(rnd)) {
                        unexpectedEx.set(true);
                    }
                }
            };
            threads[i].start();
        }

        for (int i = 0; i < nThreads; i += 1) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                e.printStackTrace(System.out);
                unexpectedEx.set(true);
            }
        }

        return unexpectedEx.get();
    }

    /**
     * Perform DB writes until an exception is thrown.
     *
     * @return true if an unexpected exception was thrown.
     */
    private boolean runLoad(Random rnd) {

        final DatabaseEntry key = new DatabaseEntry();
        final DatabaseEntry value = new DatabaseEntry();

        boolean unexpectedEx = false;

        while (true) {
            try {
                IntegerBinding.intToEntry(rnd.nextInt(100000), key);
                IntegerBinding.intToEntry(rnd.nextInt(), value);

                final Transaction txn = env.beginTransaction(null, null);
                try {
                    db.put(txn, key, value);
                    txn.commit(rnd.nextBoolean() ?
                               Durability.COMMIT_NO_SYNC :
                               Durability.COMMIT_WRITE_NO_SYNC);
                } finally {
                    txn.abort();
                }

                if (rnd.nextInt(1000) == 0) {
                    env.checkpoint(new CheckpointConfig().setForce(true));
                }
            } catch (Throwable e) {
                if (isGeneratedException(e)) {
                    log("Generated exception, continuing...");
                    e.printStackTrace(System.out);
                } else {
                    log("***Unexpected exception!");
                    e.printStackTrace(System.out);
                    unexpectedEx = true;
                }
                if (!env.isValid()) {
                    return unexpectedEx;
                }
                /*
                 * Continue to see if log becomes corrupted by writing in an
                 * environment where a problem occurred but the environment was
                 * not invalidated.
                 */
            }
        }
    }

    private static boolean isGeneratedException(Throwable e) {
        while (e != null) {
            if (e.getMessage() != null &&
                e.getMessage().contains(EXCEPTION_MSG)) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }

    private void openEnv() {
        if (env != null) {
            throw new IllegalStateException();
        }
        final EnvironmentConfig config = new EnvironmentConfig();
        config.setAllowCreate(true);
        config.setTransactional(true);
        config.setConfigParam(EnvironmentConfig.CLEANER_THREADS,
                              String.valueOf(nCleanerThreads));
        config.setCacheSize(cacheMb * (1024 * 1024));

        env = new Environment(new File(homeDir), config);
    }

    private void openDb() {
        if (db != null) {
            throw new IllegalStateException();
        }
        final DatabaseConfig config = new DatabaseConfig();
        config.setAllowCreate(true);
        config.setTransactional(true);
        db = env.openDatabase(null, DB_NAME, config);
    }

    private void closeDbAndEnv() {
        if (db != null) {
            try {
                db.close();
            } catch (final Throwable e) {
                log("Ignoring exception closing db...");
                e.printStackTrace(System.out);
            } finally {
                db = null;
            }
        }
        if (env != null) {
            try {
                log("Closing environment...");
                env.close();
            } catch (final Throwable e) {
                log("Ignoring exception closing env...");
                e.printStackTrace(System.out);
            } finally {
                env = null;
            }
        }
    }

    private static void log(final String msg) {
        System.out.println(DATE_FORMAT.format(System.currentTimeMillis()) +
                           " " + msg);
    }

    private class ErrorInjector extends FileManager.DefaultRandomAccessFile {

        /* The larger the value, the less likely an error is generated. */
        private static final int ERROR_PROPABILITY = 1000000;

        private final Random rnd = new Random();

        public ErrorInjector(final String name, final String mode)
            throws FileNotFoundException {

            super(name, mode);
        }

        @Override
        public void close()
            throws IOException {

            generateError();
            super.close();
        }

        @Override
        public long getFilePointer()
            throws IOException {

            generateError();
            return super.getFilePointer();
        }


        @Override
        public long length()
            throws IOException {

            generateError();
            return super.length();
        }

        @Override
        public int read()
            throws IOException {

            generateError();
            return super.read();
        }

        @Override
        public int read(final byte[] b,
                                     final int off,
                                     final int len)
            throws IOException {

            generateError();
            return super.read(b, off, len);
        }

        @Override
        public int read(final byte[] b)
            throws IOException {

            generateError();
            return super.read(b);
        }

        @Override
        public void seek(final long pos)
            throws IOException {

            generateError();
            super.seek(pos);
        }

        @Override
        public void setLength(final long newLength)
            throws IOException {

            generateError();
            super.setLength(newLength);
        }

        @Override
        public int skipBytes(final int n)
            throws IOException {

            generateError();
            return super.skipBytes(n);
        }

        @Override
        public void write(final byte[] b)
            throws IOException {

            write(b, 0, b.length);
        }

        @Override
        public void write(final byte[] b,
                                       final int off,
                                       final int len)
            throws IOException {

            for (int i = off; i < len; ++i) {
                write(b[i]);
            }
        }

        @Override
        public void write(final int b)
            throws IOException {

            generateError();
            super.write(b);
        }

        private void generateError()
            throws IOException {

            if (!injectErrors) {
                return;
            }

            switch (rnd.nextInt(ERROR_PROPABILITY)) {
            case 0:
                throw new IOException(EXCEPTION_MSG);
            case 10:
                throw new IOError(new IOException(EXCEPTION_MSG));
            case 20:
                throw new OutOfMemoryError(EXCEPTION_MSG);
            case 30:
                throw new SyncFailedException(EXCEPTION_MSG);
            case 40:
                throw new InterruptedIOException(EXCEPTION_MSG);
            case 50:
                throw new RuntimeException(EXCEPTION_MSG);
            case 60:
                throw new Error(EXCEPTION_MSG);
            }
        }
    }
}