package com.lucid.solr.sidecar;
/*
 * Copyright 2013 LucidWorks Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.ParallelAtomicReader;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.BufferedIndexInput;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SidecarIndexReader extends DirectoryReader {
  private static final Logger LOG = LoggerFactory.getLogger(SidecarIndexReader.class);
  private DirectoryReader main;
  private AtomicReader[] mainReaders;
  private AtomicReader[] parallelReaders;
  private AtomicReader[] sidecarReaders;
  private Directory dir;
  private String boostData;
  private long resourcesLastModified;
  private SidecarIndexReaderFactory factory;
  private boolean readOnly;
  private long version;
  // for testing
  int numBoosts;
  float maxBoost;
  float totalBoost;
  float totalPosBoost;
  float totalTimeBoost;
  int boostedDocs;
  private File sidecarDir;
  private IndexReader parallel;

  public SidecarIndexReader(SidecarIndexReaderFactory factory, DirectoryReader main,
          AtomicReader[] sidecarReaders,
          AtomicReader[] parallelReaders, String boostData, File sidecarDir) throws IOException {
    super(main.directory(), parallelReaders);
    assert assertSaneReaders(parallelReaders);
    //LOG.info("SidecarIndexReader: new " + this);
    this.factory = factory;
    this.main = main;
    this.parallelReaders = parallelReaders;
    this.sidecarReaders = sidecarReaders;
    //this.parallel = parallel;
    this.mainReaders = getSequentialSubReaders(main);
    resourcesLastModified = main.getVersion();
    this.version = resourcesLastModified;
    this.boostData = boostData;
    this.dir = main.directory();
    this.sidecarDir = sidecarDir;
  }
  
  // used only by assert: checks that no subs are null
  private boolean assertSaneReaders(AtomicReader[] readers) {
    for (int i = 0; i < readers.length; i++) {
      assert readers[i] != null : "null subreader " + i;
    }
    return true;
  }
  
  // don't ever let Uwe Schindler see this
  static AtomicReader[] getSequentialSubReaders(IndexReader r) {
    List<AtomicReaderContext> leaves = r.leaves();
    AtomicReader subReaders[] = new AtomicReader[leaves.size()];
    for (int i = 0; i < leaves.size(); i++) {
      subReaders[i] = leaves.get(i).reader();
    }
    return subReaders;
  }
  
  private long checkVersion() {
    long modified = 0;
      if (main.getRefCount() > 0) {
        version = main.getVersion();
      } else {
        version = 0;
      }
      modified = version;
    return modified;
  }

  public IndexReader getMain() {
    return main;
  }

  @Override
  protected synchronized void doClose() throws IOException {
    if (parallel != null) {
      parallel.close();
    }
//    if (main.getRefCount() > 0) {
//      try {
//        main.decRef();
//      } catch (AlreadyClosedException ace) {
//        System.err.println(main);
//        for (AtomicReader r : getSequentialSubReaders(main)) {
//          System.err.println(" - " + r + " refCnt=" + r.getRefCount());
//        }
//        System.err.flush();
//      }
//    }
    parallelReaders = null;
    if (sidecarDir != null) factory.safeDelete(sidecarDir);
  }

  @Override
  public long getVersion() {
    checkVersion();
    return version;
  }
  
  @Override
  public DirectoryReader doOpenIfChanged() throws CorruptIndexException, IOException {
    return doOpenIfChanged(null);
  }
  
  @Override
  protected DirectoryReader doOpenIfChanged(IndexCommit commit)
          throws CorruptIndexException, IOException {
    return openIfChangedInternal(null, null, false);
  }

  @Override
  protected DirectoryReader doOpenIfChanged(IndexWriter writer,
          boolean applyAllDeletes) throws CorruptIndexException, IOException {
    return openIfChangedInternal(null, writer, applyAllDeletes);
  }
  
  private synchronized DirectoryReader openIfChangedInternal(IndexCommit commit,
          IndexWriter writer, boolean applyAllDeletes) throws CorruptIndexException,
          IOException {
    //LOG.info("SidecarIndexReader reopen:  ", new Exception());
    boolean rebuild = false;
    long modifiedTime = checkVersion();
    // check if the reader was modified
    boolean modified = false;
    DirectoryReader newMain;
    if (commit != null) {
      newMain = DirectoryReader.openIfChanged(main, commit);
    } else if (writer != null) { // nrt
      newMain = DirectoryReader.openIfChanged(main, writer, applyAllDeletes);
    } else {
      newMain = DirectoryReader.openIfChanged(main);
    }
    if (newMain != null) {
      // main will be closed when 'this' is closed
      rebuild = true;
    } else {
      newMain = main; // unchanged
    }
    
    //LOG.info(" modified=" + modified + ", rebuild=" + rebuild);
    if (modified || rebuild) {
      // must make private copy so that we don't modify the same subreaders
      // being used for concurrent searches on the existing reader.
      AtomicReader parallelReaders[] = new AtomicReader[this.parallelReaders.length];
      System.arraycopy(this.parallelReaders, 0, parallelReaders, 0, parallelReaders.length);
      if (parallel != null) parallel.close();
      //LOG.info(" - returning new from reopen()");
      if (!rebuild) main.incRef(); // passing same main, new reader from following reopen will close this
      if (rebuild && !modified && sidecarReaders != null) { // the same resources but changed main index
//        if (sidecarReaders == null) {
//          LOG.info(" - NRT reopen, missing sidecar data, single main index");
//          return new SidecarIndexReader(factory, newMain, null, getSequentialSubReaders(newMain),
//                  boostData, sidecarDir);
//        }
        // try incremental nrt reopen
        AtomicReader[] newReaders = getSequentialSubReaders(newMain);
        if (writer != null) { // looks like nrt open - check it
          // there can be no new ones except maybe the last one
          Map<String,AtomicReader> newReadersMap = new HashMap<String,AtomicReader>();
          for (AtomicReader ar : newReaders) {
            newReadersMap.put(ar.getCombinedCoreAndDeletesKey().toString(), ar);
          }
          for (int i = 0; i < mainReaders.length; i++) {
            if (!newReadersMap.containsKey(mainReaders[i].getCombinedCoreAndDeletesKey().toString())) {
              // this segment was dropped
              //parallelReaders[i].close();
              parallelReaders[i] = null;
            } else {
              // refresh this parallel reader
              AtomicReader newReader = newReadersMap.remove(mainReaders[i].getCombinedCoreAndDeletesKey().toString());
              // verify that we have sidecar readers and that they span this segment too
              // - the last segments could have originated from NRT
              if (sidecarReaders != null && i < sidecarReaders.length) {
                //sidecarReaders[i].incRef();
                //parallelReaders[i].close();
                parallelReaders[i] = new ParallelAtomicReader(newReader, sidecarReaders[i]);
              } else {
                parallelReaders[i] = newReader;
              }
            }
          }
          if (newReadersMap.size() <= 1) { // an nrt
            List<AtomicReader> newParallelReaders = new ArrayList<AtomicReader>(newReaders.length);
            for (int i = 0; i < parallelReaders.length; i++) {
              if (parallelReaders[i] != null) { // refresh and add a parallel reader
                newParallelReaders.add(parallelReaders[i]);
              }
            }
            // and now simply add the new left-over nrt readers
            //new Exception("### newReadersMap: " + newReadersMap).printStackTrace();
            newParallelReaders.addAll(newReadersMap.values());
            SidecarIndexReader newCIR = new SidecarIndexReader(factory, newMain, sidecarReaders,
                    (AtomicReader[])newParallelReaders.toArray(new AtomicReader[0]), boostData,
                    sidecarDir);
            LOG.info("Opened new NRT SidecarIndexReader");
            return newCIR;
          }
        }
      }
      // else do a full rebuild
      LOG.info(" - full rebuild from reopen()");
      return factory.reopen(newMain, rebuild);
    } else {
      LOG.info(" - returning old from reopen()");
      return null;
    }
  }

  @Override
  public boolean isCurrent() throws CorruptIndexException, IOException {
    return main.isCurrent();
  }
  
  @Override
  public IndexCommit getIndexCommit() throws IOException {
    return main.getIndexCommit();
  }
  
}
