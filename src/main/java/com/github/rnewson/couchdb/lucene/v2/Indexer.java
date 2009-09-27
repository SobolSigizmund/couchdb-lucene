package com.github.rnewson.couchdb.lucene.v2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.log4j.Logger;
import org.mortbay.component.AbstractLifeCycle;

/**
 * Pull data from couchdb into Lucene indexes.
 * 
 * @author robertnewson
 */
public final class Indexer extends AbstractLifeCycle {

    private final Logger logger = Logger.getLogger(Indexer.class);

    private State state;

    private final Set<String> activeTasks = new HashSet<String>();

    private ScheduledExecutorService scheduler;

    public Indexer(final State state) {
        this.state = state;
    }

    @Override
    protected void doStart() throws Exception {
        scheduler = Executors.newScheduledThreadPool(5);
        scheduler.scheduleWithFixedDelay(new CouchPoller(), 0, 1, TimeUnit.MINUTES);
    }

    @Override
    protected void doStop() throws Exception {
        scheduler.shutdown();
        scheduler.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
    }

    private class CouchPoller implements Runnable {

        @Override
        public void run() {
            try {
                final String[] databases = state.couch.getAllDatabases();
                synchronized (activeTasks) {
                    for (final String databaseName : databases) {
                        if (!activeTasks.contains(databaseName)) {
                            logger.debug("Tracking " + databaseName);
                            activeTasks.add(databaseName);
                            scheduler.execute(new DatabasePuller(databaseName));
                        }
                    }
                }
            } catch (final HttpException e) {
                // Ignore.
            } catch (final IOException e) {
                // Ignore.
            }
        }
    }

    private class DatabasePuller implements Runnable {

        private final String databaseName;

        private long since;

        public DatabasePuller(final String databaseName) {
            this.databaseName = databaseName;
        }

        @Override
        public void run() {
            try {
                mapViewsToIndexes();
                readCurrentUpdateSequence();
                pullChanges();
            } catch (final IOException e) {
                logger.warn("Tracking for database " + databaseName + " interrupted by I/O exception.");
            } finally {
                untrack();
            }
        }

        private void mapViewsToIndexes() throws IOException {
            final JSONArray designDocuments = state.couch.getAllDesignDocuments(databaseName);
            for (int i = 0; i < designDocuments.size(); i++) {
                final JSONObject designDocument = designDocuments.getJSONObject(i).getJSONObject("doc");
                final String designDocumentName = designDocument.getString(Constants.ID).substring(8);
                final JSONObject fulltext = designDocument.getJSONObject("fulltext");
                if (fulltext != null) {
                    for (final Object obj : fulltext.keySet()) {
                        final String viewName = (String) obj;
                        state.locator.update(databaseName, designDocumentName, viewName, fulltext.getString(viewName));
                    }
                }
            }
        }

        private void readCurrentUpdateSequence() throws IOException {
            // TODO read highest seq field from each index or read _local/lucene
            // or something.
        }

        private void pullChanges() throws IOException {
            final String url = state.couch.url(String.format("%s/_changes?feed=continuous&since=%d&include_docs=true",
                    databaseName, since));
            state.httpClient.execute(new HttpGet(url), new ChangesResponseHandler());
        }

        private void untrack() {
            synchronized (activeTasks) {
                activeTasks.remove(databaseName);
            }
            logger.debug("Untracking " + databaseName);
        }

        private class ChangesResponseHandler implements ResponseHandler<Void> {

            @Override
            public Void handleResponse(final HttpResponse response) throws ClientProtocolException, IOException {
                final HttpEntity entity = response.getEntity();
                final BufferedReader reader = new BufferedReader(new InputStreamReader(entity.getContent(), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    final JSONObject json = JSONObject.fromObject(line);
                    System.err.println(json);
                    since = json.getLong("seq");
                }
                return null;
            }

        }

    }

}