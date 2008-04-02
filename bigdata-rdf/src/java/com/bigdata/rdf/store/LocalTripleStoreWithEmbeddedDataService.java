/*

Copyright (C) SYSTAP, LLC 2006-2008.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Jan 11, 2008
 */

package com.bigdata.rdf.store;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.bigdata.btree.IIndex;
import com.bigdata.btree.IndexMetadata;
import com.bigdata.btree.IDataSerializer.NoDataSerializer;
import com.bigdata.journal.ITx;
import com.bigdata.journal.Journal;
import com.bigdata.rdf.store.IndexWriteProc.FastRDFKeyCompression;
import com.bigdata.rdf.store.IndexWriteProc.FastRDFValueCompression;
import com.bigdata.service.DataService;
import com.bigdata.service.DataServiceIndex;
import com.bigdata.service.EmbeddedDataService;
import com.bigdata.service.EmbeddedLoadBalancerService;
import com.bigdata.service.IDataService;
import com.bigdata.service.ILoadBalancerService;
import com.bigdata.service.IMetadataService;

/**
 * A thread-safe variant that supports concurrent data load and query (the
 * {@link ScaleOutTripleStore} also supports concurrent data load and query).
 * 
 * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
 * @version $Id$
 */
public class LocalTripleStoreWithEmbeddedDataService extends AbstractLocalTripleStore {

    /**
     * The configured branching factor.
     * 
     * @see Options#BRANCHING_FACTOR
     */
    final private int branchingFactor;
    
    /**
     * The data are stored in an embedded {@link DataService} that provides
     * concurrency control.
     */
    final private EmbeddedDataService dataService;
    
    final private ILoadBalancerService loadBalancerService;
    
    /**
     * The operations on the indices are unisolated.
     * 
     * @todo providing transactional isolation is as easy as using a transaction
     *       identifier here. however, the terms and ids indices are designed to
     *       use unisolated operations and truth maintenance inherently is a
     *       transition from one consistent state to another - it can not be
     *       accomplished if there are concurrent writes on the database (unless
     *       truth maintenance reads behind from the last closure of the
     *       database while concurrent writes ahead are buffered - probably on a
     *       temporary store for later closure, e.g., still a process which must
     *       be serialized - much like a commit!)
     */
    final private long tx = ITx.UNISOLATED;
    
    final IIndex ndx_termId;
    final IIndex ndx_idTerm;
    final IIndex ndx_freeText;
    final IIndex ndx_spo;
    final IIndex ndx_pos;
    final IIndex ndx_osp;
    final IIndex ndx_just;
    
    /**
     * Options understood by the {@link LocalTripleStoreWithEmbeddedDataService}.
     * 
     * @author <a href="mailto:thompsonbry@users.sourceforge.net">Bryan Thompson</a>
     * @version $Id$
     */
    public interface Options extends AbstractLocalTripleStore.Options, DataService.Options {
        
    }
    
    /**
     * Note: Journal overflow is disabled by this class. Therefore indices will
     * never be split and will remain monolithic.
     * <p>
     * Note: The persistent state of the database will be stored in the directory
     * named by the required {@link Options#DATA_DIR} property.
     * 
     * @see Options
     */
    public LocalTripleStoreWithEmbeddedDataService(Properties properties) {
        
        super(properties);

        branchingFactor = Integer.parseInt(properties.getProperty(
                Options.BRANCHING_FACTOR, Options.DEFAULT_BRANCHING_FACTOR));
        
        loadBalancerService = new EmbeddedLoadBalancerService(
                UUID.randomUUID(), properties);
        
        /*
         * Note: The embedded data service does not support scale-out indices.
         * Use an embedded or distributed federation for that.
         * 
         * @todo the UUID of the data service might be best persisted with the
         * data service in case anything comes to rely on it, but as far as I
         * can tell nothing does or should.
         */
        
        // wrap the caller's properties to prevent side-effects.
        final Properties p = new Properties( properties );
        
        // Disable overflow.
        p.setProperty(Options.OVERFLOW_ENABLED,"false");
        
        dataService = new EmbeddedDataService(UUID.randomUUID(), p) {

            /**
             * @throws UnsupportedOperationException
             *             always since the metadata index is not used.
             */
            @Override
            public IMetadataService getMetadataService() {

                throw new UnsupportedOperationException();
                
            }

            @Override
            public ILoadBalancerService getLoadBalancerService() {

                return loadBalancerService;
                
            }

            /**
             * Returns the embedded data service IFF the given serviceUUID is
             * the UUID for the embedded data service and <code>null</code>
             * otherwise.
             */
            @Override
            public IDataService getDataService(UUID serviceUUID) {

                if (serviceUUID == null)
                    throw new IllegalArgumentException();
                
                final UUID uuid;
                try {
                    uuid = dataService.getServiceUUID();
                } catch(IOException ex) {
                    // Note: Should never happen since this is a local method call.
                    throw new RuntimeException(ex);
                }
                
                if(uuid.equals(serviceUUID)) {
                    
                    return dataService;
                    
                }

                return null; 
                
            }
            
        };
        
        log.info("Using embedded data service: "+dataService);
        
        /*
         * register indices. 
         */
        registerIndices();
        
        /*
         * create views.
         * 
         * Note: We can create views even for indices that will not be allowed
         * since an error will result if an operation is submitted for that view
         * to the data service.
         * 
         * Note: if full transactions are to be used then only the statement
         * indices and the justification indices should be assigned the
         * transaction identifier - the term:id and id:term indices ALWAYS use
         * unisolated operation to ensure consistency without write-write
         * conflicts.
         */

        ndx_termId   = new DataServiceIndex(name_termId, tx, dataService);
        ndx_idTerm   = new DataServiceIndex(name_idTerm, tx, dataService);
        ndx_freeText = new DataServiceIndex(name_freeText, tx, dataService);
        ndx_spo      = new DataServiceIndex(name_spo, tx, dataService);
        ndx_pos      = new DataServiceIndex(name_pos, tx, dataService);
        ndx_osp      = new DataServiceIndex(name_osp, tx, dataService);
        ndx_just     = new DataServiceIndex(name_just, tx, dataService);
        
    }
    
    private abstract class RegisterIndexTask implements Callable<Object> {
     
        final String name;
        
        public RegisterIndexTask(String name) {

            this.name = name;
            
        }
        
        protected IndexMetadata getIndexMetadata() {
            
            IndexMetadata metadata = new IndexMetadata(name,UUID.randomUUID());

            metadata.setBranchingFactor(branchingFactor);
            
            return metadata;
            
        }
        
        public Object call() throws Exception {
            
            log.info("Registering index: "+name);
            
            dataService.registerIndex(name, getIndexMetadata());
            
            return null;
            
        }

    }

    private class RegisterTermIdIndexTask extends RegisterIndexTask {

        public RegisterTermIdIndexTask(String name) {
            
            super(name);
            
        }

        protected IndexMetadata getIndexMetadata() {
            
            final IndexMetadata metadata = super.getIndexMetadata();

            return metadata;
            
        }
        
    }
    
    private class RegisterIdTermIndexTask extends RegisterIndexTask {

        public RegisterIdTermIndexTask(String name) {

            super(name);
            
        }

        protected IndexMetadata getIndexMetadata() {
            
            final IndexMetadata metadata = super.getIndexMetadata();

            return metadata;
            
        }
        
    }
    
    private class RegisterFreeTextIndexTask extends RegisterIndexTask {

        public RegisterFreeTextIndexTask(String name) {
            
            super(name);
            
        }

        protected IndexMetadata getIndexMetadata() {
            
            final IndexMetadata metadata = super.getIndexMetadata();
            
            metadata.setValueSerializer(NoDataSerializer.INSTANCE);

            return metadata;
            
        }
        
    }
    
    private class RegisterStatementIndexTask extends RegisterIndexTask {

        public RegisterStatementIndexTask(String name) {

            super(name);
            
        }

        protected IndexMetadata getIndexMetadata() {
         
            final IndexMetadata metadata = super.getIndexMetadata();
            
            metadata.setLeafKeySerializer(FastRDFKeyCompression.N3);
            
            metadata.setValueSerializer(new FastRDFValueCompression());

            return metadata;
            
        }
        
    }
    
    private class RegisterJustIndexTask extends RegisterIndexTask {

        public RegisterJustIndexTask(String name) {

            super(name);
            
        }

        protected IndexMetadata getIndexMetadata() {
            
            final IndexMetadata metadata = super.getIndexMetadata();
            
            metadata.setValueSerializer(NoDataSerializer.INSTANCE);

            return metadata;
            
        }
        
    }
    
    /**
     * Registers the various indices that will be made available to the client.
     */
    private void registerIndices() {
        
        log.info("");
        
        final List<Callable<Object>> tasks = new LinkedList<Callable<Object>>();

        if (lexicon) {

            tasks.add(new RegisterTermIdIndexTask(name_termId));

            tasks.add(new RegisterIdTermIndexTask(name_idTerm));

            if (textIndex) {

                tasks.add(new RegisterFreeTextIndexTask(name_freeText));

            }

        }

        if (oneAccessPath) {

            tasks.add(new RegisterStatementIndexTask(name_spo));

        } else {

            tasks.add(new RegisterStatementIndexTask(name_spo));

            tasks.add(new RegisterStatementIndexTask(name_pos));

            tasks.add(new RegisterStatementIndexTask(name_osp));

        }

        if (justify) {

            tasks.add(new RegisterJustIndexTask(name_just));

        }

        try {
            
            final List<Future<Object>> futures = writeService.invokeAll(tasks);
            
            for(Future<Object> f : futures) {
             
                // throws exception if there was a problem.
                f.get();
                
            }
            
            log.info("Registered indices.");

        } catch (InterruptedException ex) {
            
            throw new RuntimeException(ex);
            
        } catch (ExecutionException e) {
            
            throw new RuntimeException("Problem registering index", e);
            
        }

    }
    
    /**
     * @todo could be done as a single task or using invokeAll, which is how we
     *       register the indices.
     */
    public void clear() {

        log.info("");
        
        try {

            if (lexicon) {

                dataService.dropIndex(name_termId);
                
                dataService.dropIndex(name_idTerm);
                
                if (textIndex) {
                
                    dataService.dropIndex(name_freeText);
                    
                }
                
            }
            
            if (oneAccessPath) {
            
                dataService.dropIndex(name_spo);
                
            } else {
                
                dataService.dropIndex(name_spo);
                
                dataService.dropIndex(name_pos);
                
                dataService.dropIndex(name_osp);
                
            }
            
            if (justify) {
                
                dataService.dropIndex(name_just);
                
            }

        } catch (Exception ex) {

            throw new RuntimeException(ex);

        }
        
    }

    public IIndex getTermIdIndex() {

        return ndx_termId;
        
    }

    public IIndex getIdTermIndex() {

        return ndx_idTerm;

    }

    public IIndex getFullTextIndex() {

        return ndx_freeText;
        
    }

    public IIndex getSPOIndex() {

        return ndx_spo;
        
    }

    public IIndex getPOSIndex() {

        return ndx_pos;
        
    }

    public IIndex getOSPIndex() {

        return ndx_osp;
        
    }

    public IIndex getJustificationIndex() {

        return ndx_just;
        
    }

    public boolean isStable() {

        return dataService.getResourceManager().getLiveJournal().isStable();
        
    }

    public boolean isReadOnly() {

        return dataService.getResourceManager().getLiveJournal().isReadOnly();
        
    }

    /**
     * NOP - atomic unisolated operations are used.
     */
    public void commit() {
        
    }

    /**
     * NOP - atomic unisolated operations are used.
     */
    public void abort() {
        
    }

    final public void close() {
        
        log.info("");
        
        super.close();
        
        dataService.shutdown();
        
    }
    
    final public void closeAndDelete() {

        log.info("");

        super.closeAndDelete();
        
        dataService.shutdown();
        
        dataService.getResourceManager().deleteResources();
        
    }
    
    /**
     * This store is safe for concurrent operations.
     */
    public boolean isConcurrent() {

        return true;
        
    }
    
}
