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
import java.util.Collections;
import java.util.HashMap;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.LocalSolrQueryRequest;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.CommitUpdateCommand;
import org.apache.solr.update.DeleteUpdateCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SidecarIndexReaderFactoryTest extends SolrTestCaseJ4 {

  private final File solrHomeDirectory = new File(TEMP_DIR, SidecarIndexReaderFactoryTest.class.getName());

  private final static String SOLR_XML = " <solr persistent=\"true\"> " +
          "<cores adminPath=\"/admin/cores\" defaultCoreName=\"target\">  " +
          "<core name=\"target\" instanceDir=\"target\" loadOnStartup=\"true\"   /> " +
          "<core name=\"source\" instanceDir=\"source\" loadOnStartup=\"true\"    /> " +
          "</cores> " +
          "</solr>";
  
  static {
    System.setProperty("solr.directoryFactory", "solr.StandardDirectoryFactory");
  }
  CoreContainer cc = null;
  
  private CoreContainer init() throws Exception {

    if (solrHomeDirectory.exists()) {
      FileUtils.deleteDirectory(solrHomeDirectory);
    }
    assertTrue("Failed to mkdirs workDir", solrHomeDirectory.mkdirs());
    
    copyMinConf(new File(solrHomeDirectory, "source"));
    File target = new File(solrHomeDirectory, "target");
    copyMinConf(target);
    // modify the target to add SidecarIndexReaderFactory
    File targetSolrConf = new File(target, "conf" + File.separator + "solrconfig.xml");
    String xml = FileUtils.readFileToString(targetSolrConf, "UTF-8");
    xml = xml.replaceFirst("<!-- EDIT_SIDECAR_START", "");
    xml = xml.replaceFirst("EDIT_SIDECAR_END -->", "");
    FileUtils.writeStringToFile(targetSolrConf, xml, "UTF-8");
    File solrXml = new File(solrHomeDirectory, "solr.xml");
    FileUtils.write(solrXml, SOLR_XML, IOUtils.CHARSET_UTF_8.toString());
    final CoreContainer cores = new CoreContainer(solrHomeDirectory.getAbsolutePath());
    cores.load();

    //cores.setPersistent(false);
    return cores;
  }
  
  @Before
  public void before() throws Exception {
    cc = init();
  }
  
  @After
  public void after() throws Exception {
    cc.shutdown();
    if (solrHomeDirectory.exists()) {
      FileUtils.deleteDirectory(solrHomeDirectory);
    }
  }
  
  private void populate() throws Exception {
    // populate both indexes
    SolrCore source = cc.getCore("source");
    try {
      for (int i = 99; i >= 0; i--) {
        AddUpdateCommand cmd = new AddUpdateCommand(makeReq(source));
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "id" + i);
        doc.addField("side1_t", "foo bar side1 " + i);
        doc.addField("side2_t", "blah blah side2 " + i);
        cmd.solrDoc = doc;
        source.getUpdateHandler().addDoc(cmd);
      }
      source.getUpdateHandler().commit(new CommitUpdateCommand(makeReq(source), false));
    } finally {
      source.close();
    }
    SolrCore target = cc.getCore("target");
    try {
      for (int i = 0; i < 101; i++) {
        AddUpdateCommand cmd = new AddUpdateCommand(makeReq(target));
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "id" + i);
        doc.addField("text", "document " + i);
        cmd.solrDoc = doc;
        target.getUpdateHandler().addDoc(cmd);
        if (i == 99) {
          target.getUpdateHandler().commit(new CommitUpdateCommand(makeReq(target), false));
        }
      }
      target.getUpdateHandler().commit(new CommitUpdateCommand(makeReq(target), false));
    } finally {
      target.close();
    }
  }
  
  @Test
  public void testBasics() throws Exception {
    System.err.println(cc.getAllCoreNames());
    populate();
    SolrCore target = cc.getCore("target");
    SolrIndexSearcher searcher = target.getSearcher().get();
    Query q = new MatchAllDocsQuery();
    try {
      // verify the stored parts
      TopDocs td = searcher.search(q, 101);
      assertNotNull(td);
      for (ScoreDoc sd : td.scoreDocs) {
        Document doc = searcher.doc(sd.doc);
        String[] vals = doc.getValues("id");
        assertNotNull(vals);
        assertEquals(1, vals.length);
        String id = vals[0];
        vals = doc.getValues("text");
        assertNotNull(vals);
        assertEquals(1, vals.length);
        if (!id.equals("id100")) {
          // should have also the sidecar fields
          vals = doc.getValues("side1_t");
          assertNotNull(vals);
          assertEquals(1, vals.length);
          vals = doc.getValues("side2_t");
          assertNotNull(vals);
          assertEquals(1, vals.length);
        } else {
          // should not have the sidecar fields
          vals = doc.getValues("side1_t");
          assertTrue(vals == null || vals.length == 0);
          vals = doc.getValues("side2_t");
          assertTrue(vals == null || vals.length == 0);
        }
      }
      // verify the inverted parts
      q = new TermQuery(new Term("side1_t", "foo"));
      td = searcher.search(q, 101);
      assertEquals(100, td.totalHits);
      q = new TermQuery(new Term("side2_t", "blah"));
      td = searcher.search(q, 101);
      assertEquals(100, td.totalHits);
    } finally {
      searcher.close();
      target.close();
    }
  }

  @Test
  public void testChanges() throws Exception {
    populate();
    // add some docs, overwriting some of the existing ones
    SolrCore target = cc.getCore("target");
    try {
      for (int i = 50; i < 150; i++) {
        AddUpdateCommand cmd = new AddUpdateCommand(makeReq(target));
        cmd.overwrite = true;
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", "id" + i);
        doc.addField("text", "new document " + i);
        cmd.solrDoc = doc;
        target.getUpdateHandler().addDoc(cmd);
      }
      target.getUpdateHandler().commit(new CommitUpdateCommand(makeReq(target), false));
    } finally {
      target.close();
    }
    target = cc.getCore("target");
    SolrIndexSearcher searcher = target.getSearcher().get();
    Query q = new MatchAllDocsQuery();
    try {
      // verify the stored parts
      TopDocs td = searcher.search(q, 151);
      assertNotNull(td);
      for (ScoreDoc sd : td.scoreDocs) {
        Document doc = searcher.doc(sd.doc);
        System.err.println(doc);
      }
    } finally {
      searcher.close();
      target.close();
    }
  }
  
  private LocalSolrQueryRequest makeReq(SolrCore core, String... q) {
    if (q.length == 1) {
      return new LocalSolrQueryRequest(core,
          q[0], null, 0, 20, new HashMap<String, String>());
    }
    if (q.length % 2 != 0) {
      throw new RuntimeException("The length of the string array (query arguments) needs to be even");
    }
    NamedList.NamedListEntry[] entries = new NamedList.NamedListEntry[q.length / 2];
    for (int i = 0; i < q.length; i += 2) {
      entries[i / 2] = new NamedList.NamedListEntry<String>(q[i], q[i + 1]);
    }
    return new LocalSolrQueryRequest(core, new NamedList<Object>(entries));
  }

  
}
