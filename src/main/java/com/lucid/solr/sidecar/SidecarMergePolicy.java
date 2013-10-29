package com.lucid.solr.sidecar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.index.SegmentInfoPerCommit;
import org.apache.lucene.index.SegmentInfos;

public class SidecarMergePolicy extends MergePolicy {
  boolean doMerge = true;
  int[] targets;
  
  private final boolean useCompoundFile;

  SidecarMergePolicy(int[] targets, boolean useCompoundFile) {
    this.useCompoundFile = useCompoundFile;
    this.targets = targets;
  }
  
  @Override
  public void close() {}
  
  @Override
  public MergeSpecification findMerges(MergeTrigger mergeTrigger, SegmentInfos segmentInfos)
      throws CorruptIndexException, IOException {
    //System.err.println("findMerges");
    MergeSpecification ms = new MergeSpecification();
    if (doMerge) {
      List<SegmentInfoPerCommit> mergeInfos = new ArrayList<SegmentInfoPerCommit>();
      int target = 0;
      int count = 0;
      for (int i = 0; i < segmentInfos.size() && target < targets.length; i++) {
        SegmentInfoPerCommit commit = segmentInfos.info(i);
        SegmentInfo info = commit.info;
        if (info.getDocCount() == targets[target]) { // this one is ready
          target++;
          continue;
        }
        if (count + info.getDocCount() <= targets[target]) {
          mergeInfos.add(commit);
          count += info.getDocCount();
        } else {
          assert info.getDocCount() < targets[target] : "doc count should be smaller than the current target";
          if (mergeInfos.size() > 0) {
            OneMerge om = new OneMerge(mergeInfos);
            ms.add(om);
          }
          count = 0;
          mergeInfos = new ArrayList<SegmentInfoPerCommit>();
        }
      }
      if (mergeInfos.size() > 0) {
        OneMerge om = new OneMerge(mergeInfos);
        ms.add(om);        
      }
      //doMerge = false;
      return ms;
    }
    return null;
  }
  
  @Override
  public MergeSpecification findForcedMerges(SegmentInfos segmentInfos,
      int maxSegmentCount, Map<SegmentInfoPerCommit,Boolean> segmentsToOptimize)
      throws CorruptIndexException, IOException {
    System.err.println("findForcedMerges");
    return null;
  }
  
  @Override
  public MergeSpecification findForcedDeletesMerges(
      SegmentInfos segmentInfos) throws CorruptIndexException, IOException {
    System.err.println("findForcedDeletesMerges");
    return null;
  }
  
  @Override
  public boolean useCompoundFile(SegmentInfos segments, SegmentInfoPerCommit newSegment) {
    return useCompoundFile;
  }

}
