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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.DocumentStoredFieldVisitor;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.index.ParallelCompositeReader;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.IndexReaderFactory;
import org.apache.solr.core.SolrCore;
import org.apache.solr.core.StandardIndexReaderFactory;
import org.apache.solr.search.SolrIndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SidecarIndexReaderFactory extends IndexReaderFactory {
  // XXX nocommit
  public static String DEFAULT_DATA_PATH = ".";
  
  private static final Logger LOG = LoggerFactory.getLogger(SidecarIndexReaderFactory.class);
  public static enum Mode {
    /** Normalize against max boost value. */
    max,
    /** Normalize against a sum of all aggregated boost values, multiply by 100.
     */
    total,
    /** no normalization, use raw boost values. */
    none
  };
  StandardIndexReaderFactory standardFactory;
  String sidecarIndexLocation = "sidecar-index";
  String sourceCollection;
  String boostField;
  String docIdField;
  File sidecarIndex;
  boolean enabled;
  Mode mode = Mode.max;
  float multiplier;
  SolrCore currentCore;
  Set<String> parallelFields = new HashSet<String>();

  @Override
  public void init(NamedList args) {
    super.init(args);
    docIdField = (String)args.get("docIdField");
    sourceCollection = (String)args.get("sourceCollection");
    boostField = (String)args.get("boostField");
    enabled = (Boolean)args.get("enabled");
    //LOG.info("CIRF INIT called, enabled=" + enabled);
    String modeString = (String)args.get("mode");
    if (modeString != null) {
      try {
        Mode m = Mode.valueOf(modeString);
        mode = m;
      } catch (Exception e) {
        
      }
    }
    if (mode == Mode.total) {
      multiplier = 100.0f;
    } else {
      multiplier = 1.0f;
    }
    standardFactory = new StandardIndexReaderFactory();
    standardFactory.init(args);    
  }
  
  // for testing
  public void init(String docIdField, String sourceCollection,
      String boostField, Mode mode) {
    this.docIdField = docIdField;
    this.sourceCollection = sourceCollection;
    this.boostField = boostField;
    this.enabled = true;
    standardFactory = new StandardIndexReaderFactory();
    this.mode = mode;
    if (mode == Mode.total) {
      multiplier = 100.0f;
    } else {
      multiplier = 1.0f;
    }
  }
  
  @Override
  public DirectoryReader newReader(IndexWriter writer, SolrCore core) throws IOException {
    return newReaderInternal(null, writer, core);
  }
  
  @Override
  public DirectoryReader newReader(Directory indexDir, SolrCore core)
          throws IOException {
    return newReaderInternal(indexDir, null, core);
  }
  
  DirectoryReader newReaderInternal(Directory indexDir, IndexWriter writer, SolrCore core) throws IOException {
    DirectoryReader main = null;
    if (writer != null) {
      main = standardFactory.newReader(writer,  core);
    } else {
      main = standardFactory.newReader(indexDir, core);
    }
    if(!enabled) {
      LOG.info("Sidecar index not enabled");
      return main;
    }
    currentCore = core;
    CoreContainer container = core.getCoreDescriptor().getCoreContainer();
    SolrCore source = container.getCore(sourceCollection);
    if (source == null) {
      LOG.info("Source collection '" + sourceCollection + "' not present, sidecar index is disabled.");
      try {
        return new SidecarIndexReader(this, main, null, SidecarIndexReader.getSequentialSubReaders(main), sourceCollection, null);
      } catch (Exception e1) {
        LOG.warn("Unexpected exception, returning single main index", e1);
        return main;
      }
    }
    if (source.isClosed()) {
      LOG.info("Source collection '" + sourceCollection + "' is closed, sidecar index is disabled.");
      try {
        return new SidecarIndexReader(this, main, null, SidecarIndexReader.getSequentialSubReaders(main), sourceCollection, null);
      } catch (Exception e1) {
        LOG.warn("Unexpected exception, returning single main index", e1);
        return main;
      }
    }
    DirectoryReader parallel = null;
    SolrIndexSearcher searcher = null;
    try {
      searcher = source.getNewestSearcher(true).get();
      parallel = buildParallelReader(main, searcher, true);
    } finally {
      if (searcher != null) {
        LOG.info("-- closing " + searcher);
        searcher.close();
      }
      source.close();
    }
    return parallel;
  }
  
  DirectoryReader reopen(DirectoryReader newMain, boolean rebuild) throws IOException {
    CoreContainer container = currentCore.getCoreDescriptor().getCoreContainer();
    SolrCore source = container.getCore(sourceCollection);
    if (source == null) {
      LOG.info("Source collection '" + sourceCollection + "' not present, sidecar index is disabled.");
      try {
        return new SidecarIndexReader(this, newMain, null, SidecarIndexReader.getSequentialSubReaders(newMain), sourceCollection, null);
      } catch (Exception e1) {
        LOG.warn("Unexpected exception, returning single main index", e1);
        return newMain;
      }
    }
    if (source.isClosed()) {
      LOG.info("Source collection '" + sourceCollection + "' is closed, sidecar index is disabled.");
      try {
        return new SidecarIndexReader(this, newMain, null, SidecarIndexReader.getSequentialSubReaders(newMain), sourceCollection, null);
      } catch (Exception e1) {
        LOG.warn("Unexpected exception, returning single main index", e1);
        return newMain;
      }
    }
    DirectoryReader parallel = null;
    SolrIndexSearcher searcher = null;
    try {
      searcher = source.getNewestSearcher(true).get();
      parallel = buildParallelReader(newMain, searcher, rebuild);
    } finally {
      if (searcher != null && searcher.getIndexReader().getRefCount() > 0) {
        LOG.info("-- closing " + searcher);
        searcher.close();
      }
      if (source != null) {
        source.close();
      }
    }
    return parallel;
  }  
  
  DirectoryReader buildParallelReader(DirectoryReader main, SolrIndexSearcher source, boolean rebuild) {
    try {
      if (source == null) {
        throw new Exception("Source collection is missing.");
      }
      // create as a sibling path of the main index
      Directory d = main.directory();
      File primaryDir = null;
      if (d instanceof FSDirectory) {
        String path = ((FSDirectory)d).getDirectory().getPath();
        primaryDir = new File(path);
        sidecarIndex = new File(primaryDir.getParentFile(), sidecarIndexLocation);
      } else {
        String secondaryPath = System.getProperty("java.io.tmpdir") + File.separator + 
          sidecarIndexLocation + "-" + System.currentTimeMillis();
        sidecarIndex = new File(secondaryPath);
      }
      // create a new tmp dir for the secondary indexes
      File secondaryIndex = new File(sidecarIndex, System.currentTimeMillis() + "-index");
      if (rebuild) {
        safeDelete(sidecarIndex);
      }
      parallelFields.addAll(source.getFieldNames());
      parallelFields.remove("id");
      LOG.debug("building a new index");
      Directory dir = FSDirectory.open(secondaryIndex);
      if (IndexWriter.isLocked(dir)) {
        // try forcing unlock
        try {
          IndexWriter.unlock(dir);
        } catch (Exception e) {
          LOG.warn("Failed to unlock " + secondaryIndex);
        }
      }
      int[] mergeTargets;
      AtomicReader[] subReaders = SidecarIndexReader.getSequentialSubReaders(main);
      if (subReaders == null || subReaders.length == 0) {
        mergeTargets = new int[]{main.maxDoc()};
      } else {
        mergeTargets = new int[subReaders.length];
        for (int i = 0; i < subReaders.length; i++) {
          mergeTargets[i] = subReaders[i].maxDoc();
        }
      }
      Version ver = currentCore.getLatestSchema().getDefaultLuceneMatchVersion();
      IndexWriterConfig cfg = new IndexWriterConfig(ver, currentCore.getLatestSchema().getAnalyzer());
      //cfg.setInfoStream(System.err);
      cfg.setMergeScheduler(new SerialMergeScheduler());
      cfg.setMergePolicy(new SidecarMergePolicy(mergeTargets, false));
      IndexWriter iw = new IndexWriter(dir, cfg);
      LOG.info("processing " + main.maxDoc() + " docs / " + main.numDeletedDocs() + " dels in main index");
      int boostedDocs = 0;
      Bits live = MultiFields.getLiveDocs(main);
      
      int targetPos = 0;
      int nextTarget = mergeTargets[targetPos];
      BytesRef idRef = new BytesRef();
      for (int i = 0; i < main.maxDoc(); i++) {
        if (i == nextTarget) {
          iw.commit();
          nextTarget = nextTarget + mergeTargets[++targetPos];
        }
        if (live != null && !live.get(i)) {
          addDummy(iw); // this is required to preserve doc numbers.
          continue;
        } else {
          DocumentStoredFieldVisitor visitor = new DocumentStoredFieldVisitor(docIdField);
          main.document(i, visitor);
          Document doc = visitor.getDocument();
          // get docId
          String id = doc.get(docIdField);
          if (id == null) {
            LOG.debug("missing id, docNo=" + i);
            addDummy(iw);
            continue;
          } else {
            // find the data, if any
            doc = lookup(source, id, idRef, parallelFields);
            if (doc == null) {
              LOG.debug("missing boost data, docId=" + id);
              addDummy(iw);
              continue;
            } else {
              LOG.debug("adding boost data, docId=" + id + ", b=" + doc);
              iw.addDocument(doc);
              boostedDocs++;
            }
          }
        }
      }
      iw.close();
      DirectoryReader other = DirectoryReader.open(dir);
      LOG.info("SidecarIndexReader with " + boostedDocs + " boosted documents.");
      SidecarIndexReader pr = createSidecarIndexReader(main, other, sourceCollection, secondaryIndex);
      return pr;
    } catch (Exception e) {
      LOG.warn("Unable to build parallel index: " + e.toString(), e);
      LOG.warn("Proceeding with single main index.");
      try {
        return new SidecarIndexReader(this, main, null, SidecarIndexReader.getSequentialSubReaders(main), sourceCollection, null);
      } catch (Exception e1) {
        LOG.warn("Unexpected exception, returning single main index", e1);
        return main;
      }
    }
  }
  
  private SidecarIndexReader createSidecarIndexReader (DirectoryReader main,
          DirectoryReader sidecar, String sourceCollection,
          File secondaryIndex) throws IOException {
    ParallelCompositeReader parallel = new ParallelCompositeReader(false, main, sidecar);
    AtomicReader[] parReaders = SidecarIndexReader.getSequentialSubReaders(parallel);
    AtomicReader[] readers = Arrays.copyOf(parReaders, parReaders.length, AtomicReader[].class);
    for (AtomicReader reader : readers) {
      reader.incRef();
    }
    parallel.close();
    SidecarIndexReader pr = new SidecarIndexReader(this, main,
            SidecarIndexReader.getSequentialSubReaders(sidecar), readers, sourceCollection,
            secondaryIndex);
    return pr;
  }
  
  private void addDummy(IndexWriter iw) throws IOException {
    Document dummy = new Document();
    Field f = new Field("_" + boostField, "d", StringField.TYPE_NOT_STORED);
    dummy.add(f);
    iw.addDocument(dummy);
  }
  
  private Document lookup(SolrIndexSearcher lookup, String id, BytesRef idRef, Set<String> fields) throws IOException {
    idRef.copyChars(id);
    Term t = new Term("id", idRef);
    if (lookup.docFreq(t) == 0) {
      return null;
    }
    
    int docId = lookup.getFirstMatch(t);
    if (docId == -1) {
      return null;
    }
    Document doc = lookup.doc(docId, fields);
    if (doc == null) {
      return null;
    }
    doc.removeFields("id");
    return doc;
  }

  // deal with locked files
  boolean safeDelete(File f) {
    if (f.isDirectory()) {
      File[] files = f.listFiles();
      boolean res = true;
      for (File f1 : files) {
        if (!safeDelete(f1)) {
          res = false;
          f1.deleteOnExit();
        }
      }
      if (!f.delete()) {
        f.deleteOnExit();
        res = false;
      }
      return res;
    }
   try {
     boolean res = f.delete();
     if (!res) {
       f.deleteOnExit();
     } else {
     }
     return res;
   } catch (Exception e) {
     LOG.warn("Can't delete old sidecar indexes: " + e.getMessage());
     return false;
   }
  }
  
  public String getSourceCollection() {
    return sourceCollection;
  }

  public String getBoostField() {
    return boostField;
  }

  public String getDocIdField() {
    return docIdField;
  }

  public boolean isEnabled() {
    return enabled;
  }  
}
