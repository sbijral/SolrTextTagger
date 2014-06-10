/*
 This software was produced for the U. S. Government
 under Contract No. W15P7T-11-C-F600, and is
 subject to the Rights in Noncommercial Computer Software
 and Noncommercial Computer Software Documentation
 Clause 252.227-7014 (JUN 1995)

 Copyright 2013 The MITRE Corporation. All Rights Reserved.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

package org.opensextant.solrtexttagger;

import com.google.common.io.CharStreams;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.core.StopFilterFactory;
import org.apache.lucene.analysis.util.TokenFilterFactory;
import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.index.ReaderUtil;
import org.apache.lucene.index.Terms;
import org.apache.lucene.queries.function.FunctionValues;
import org.apache.lucene.queries.function.ValueSource;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.IntsRef;
import org.apache.lucene.util.OpenBitSet;
import org.apache.solr.analysis.TokenizerChain;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.MapSolrParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.handler.RequestHandlerBase;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.BitDocSet;
import org.apache.solr.search.DocList;
import org.apache.solr.search.DocSet;
import org.apache.solr.search.DocSlice;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SolrReturnFields;
import org.apache.solr.search.SyntaxError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Scans posted text, looking for matching strings in the Solr index.
 * The public static final String members are request parameters.
 *
 * @author David Smiley - dsmiley@mitre.org
 */
public class TaggerRequestHandler extends RequestHandlerBase {

  /** Request parameter. */
  public static final String OVERLAPS = "overlaps";
  /** Request parameter. */
  public static final String TAGS_LIMIT = "tagsLimit";
  /** Request parameter. */
  public static final String MATCH_TEXT = "matchText";
  /** Request parameter. */
  public static final String SKIP_ALT_TOKENS = "skipAltTokens";
  /** Request parameter. */
  public static final String IGNORE_STOPWORDS = "ignoreStopwords";
  /** Request parameter. */
  public static final String XML_OFFSET_ADJUST = "xmlOffsetAdjust";

  private final Logger log = LoggerFactory.getLogger(getClass());

  @Override
  public void handleRequestBody(SolrQueryRequest req, SolrQueryResponse rsp) throws Exception {
    setTopInitArgsAsInvariants(req);

    //--Read params
    final String indexedField = req.getParams().get("field");
    if (indexedField == null)
      throw new RuntimeException("required param 'field'");

    final TagClusterReducer tagClusterReducer;
    String overlaps = req.getParams().get(OVERLAPS);
    if (overlaps == null || overlaps.equals("NO_SUB")) {
      tagClusterReducer = TagClusterReducer.NO_SUB;
    } else if (overlaps.equals("ALL")) {
      tagClusterReducer = TagClusterReducer.ALL;
    } else if (overlaps.equals("LONGEST_DOMINANT_RIGHT")) {
      tagClusterReducer = TagClusterReducer.LONGEST_DOMINANT_RIGHT;
    } else {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "unknown tag overlap mode: "+overlaps);
    }

    final int rows = req.getParams().getInt(CommonParams.ROWS, 10000);
    final int tagsLimit = req.getParams().getInt(TAGS_LIMIT, 1000);
    final boolean addMatchText = req.getParams().getBool(MATCH_TEXT, false);
    final SchemaField idSchemaField = req.getSchema().getUniqueKeyField();
    final boolean skipAltTokens = req.getParams().getBool(SKIP_ALT_TOKENS, false);
    final boolean ignoreStopWords = req.getParams().getBool(IGNORE_STOPWORDS,
            fieldHasIndexedStopFilter(indexedField, req));
    final boolean xmlOffsetAdjust = req.getParams().getBool(XML_OFFSET_ADJUST, false);

    //--Get posted data
    Reader reader = null;
    Iterable<ContentStream> streams = req.getContentStreams();
    if (streams != null) {
      Iterator<ContentStream> iter = streams.iterator();
      if (iter.hasNext()) {
        reader = iter.next().getReader();
      }
      if (iter.hasNext()) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
            getClass().getSimpleName()+" does not support multiple ContentStreams");
      }
    }
    if (reader == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          getClass().getSimpleName()+" requires text to be POSTed to it");
    }
    final String bufferedInput;
    if (addMatchText || xmlOffsetAdjust) {
      //Read the input fully into a String buffer that we'll need later,
      // then replace the input with a reader wrapping the buffer.
      bufferedInput = CharStreams.toString(reader);
      reader.close();
      reader = new StringReader(bufferedInput);
    } else {
      bufferedInput = null;//not used
    }

    final XmlOffsetCorrector xmlOffsetCorrector;
    if (xmlOffsetAdjust) {
      try {
        xmlOffsetCorrector = new XmlOffsetCorrector(bufferedInput);
      } catch (XMLStreamException e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
                "Expecting XML but wasn't: " + e.toString(), e);
      }
    } else {
      xmlOffsetCorrector = null;
    }

    final SolrIndexSearcher searcher = req.getSearcher();
    final OpenBitSet matchDocIdsBS = new OpenBitSet(searcher.maxDoc());
    final List tags = new ArrayList(2000);

    try {
      Analyzer analyzer = req.getSchema().getField(indexedField).getType().getQueryAnalyzer();
      TokenStream tokenStream = analyzer.tokenStream("", reader);//TODO consider string variant
      try {
        Terms terms = searcher.getAtomicReader().terms(indexedField);
        if (terms == null)
          throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
              "field "+indexedField+" has no indexed data");
        Tagger tagger = new Tagger(terms, computeDocCorpus(req), tokenStream, tagClusterReducer,
                skipAltTokens, ignoreStopWords) {
          @SuppressWarnings("unchecked")
          @Override
          protected void tagCallback(int startOffset, int endOffset, Object docIdsKey) {
            if (tags.size() >= tagsLimit)
              return;
            if (xmlOffsetCorrector != null) {
              int[] offsetPair = xmlOffsetCorrector.correctPair(startOffset, endOffset);
              if (offsetPair == null) {
                log.debug("Discarded offsets [{}, {}] because couldn't balance XML.",
                        startOffset, endOffset);
                return;
              }
              startOffset = offsetPair[0];
              endOffset = offsetPair[1];
            }

            NamedList tag = new NamedList();
            tag.add("startOffset", startOffset);
            tag.add("endOffset", endOffset);
            if (addMatchText)
              tag.add("matchText", bufferedInput.substring(startOffset, endOffset));
            //below caches, and also flags matchDocIdsBS
            tag.add("ids", lookupSchemaDocIds(docIdsKey));
            tags.add(tag);
          }

          Map<Object,List> docIdsListCache = new HashMap<Object, List>(2000);

          ValueSourceAccessor uniqueKeyCache = new ValueSourceAccessor(searcher,
              idSchemaField.getType().getValueSource(idSchemaField, null));

          @SuppressWarnings("unchecked")
          private List lookupSchemaDocIds(Object docIdsKey) {
            List schemaDocIds = docIdsListCache.get(docIdsKey);
            if (schemaDocIds != null)
              return schemaDocIds;
            IntsRef docIds = lookupDocIds(docIdsKey);
            //translate lucene docIds to schema ids
            schemaDocIds = new ArrayList(docIds.length);
            for (int i = docIds.offset; i < docIds.offset + docIds.length; i++) {
              int docId = docIds.ints[i];
              matchDocIdsBS.set(docId);//also, flip docid in bitset
              schemaDocIds.add(uniqueKeyCache.objectVal(docId));//translates here
            }
            assert !schemaDocIds.isEmpty();

            docIdsListCache.put(docIds, schemaDocIds);
            return schemaDocIds;
          }

        };
        tagger.enableDocIdsCache(2000);//TODO configurable
        tagger.process();
      } finally {
        tokenStream.close();
      }
    } finally {
      reader.close();
    }
    rsp.add("tagsCount",tags.size());
    rsp.add("tags", tags);

    rsp.setReturnFields(new SolrReturnFields( req ));

    //Now we must supply a Solr DocList and add it to the response.
    //  Typically this is gotten via a SolrIndexSearcher.search(), but in this case we
    //  know exactly what documents to return, the order doesn't matter nor does
    //  scoring.
    //  Ideally an implementation of DocList could be directly implemented off
    //  of a BitSet, but there are way too many methods to implement for a minor
    //  payoff.
    int matchDocs = (int) matchDocIdsBS.cardinality();
    int[] docIds = new int[ Math.min(rows, matchDocs) ];
    DocIdSetIterator docIdIter = matchDocIdsBS.iterator();
    for (int i = 0; i < docIds.length; i++) {
      docIds[i] = docIdIter.nextDoc();
    }
    DocList docs = new DocSlice(0, docIds.length, docIds, null, matchDocs, 1f);
    rsp.add("response", docs);//Solr's standard name for matching docs in response
  }

  /**
   * The set of documents matching the provided 'fq' (filter query). Don't include deleted docs
   * either. If null is returned, then all docs are available.
   */
  private Bits computeDocCorpus(SolrQueryRequest req) throws SyntaxError, IOException {
    final String corpusFilterQuery = req.getParams().get("fq");
    final SolrIndexSearcher searcher = req.getSearcher();
    final Bits docBits;
    if (corpusFilterQuery != null) {
      QParser qParser = QParser.getParser(corpusFilterQuery, null, req);
      Query filterQuery;
      try {
        filterQuery = qParser.parse();
      } catch (SyntaxError e) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, e);
      }
      final DocSet docSet = searcher.getDocSet(filterQuery);//hopefully in the cache
      //note: before Solr 4.7 we could call docSet.getBits() but no longer.
      if (docSet instanceof BitDocSet) {
        docBits = ((BitDocSet)docSet).getBits();
      } else {
        docBits = new Bits() {

          @Override
          public boolean get(int index) {
            return docSet.exists(index);
          }

          @Override
          public int length() {
            return searcher.maxDoc();
          }
        };
      }
    } else {
      docBits = searcher.getAtomicReader().getLiveDocs();
    }
    return docBits;
  }

  private boolean fieldHasIndexedStopFilter(String field, SolrQueryRequest req) {
    FieldType fieldType = req.getSchema().getFieldType(field);
    Analyzer analyzer = fieldType.getAnalyzer();//index analyzer
    if (analyzer instanceof TokenizerChain) {
      TokenizerChain tokenizerChain = (TokenizerChain) analyzer;
      TokenFilterFactory[] tokenFilterFactories = tokenizerChain.getTokenFilterFactories();
      for (TokenFilterFactory tokenFilterFactory : tokenFilterFactories) {
        if (tokenFilterFactory instanceof StopFilterFactory)
          return true;
      }
    }
    return false;
  }

  /**
   * This request handler supports configuration options defined at the top level as well as
   * those in typical Solr 'defaults', 'appends', and 'invariants'.  The top level ones are treated
   * as invariants.
   */
  private void setTopInitArgsAsInvariants(SolrQueryRequest req) {
    // First convert top level initArgs to SolrParams
    HashMap<String,String> map = new HashMap<String,String>(initArgs.size());
    for (int i=0; i<initArgs.size(); i++) {
      Object val = initArgs.getVal(i);
      if (val != null && !(val instanceof NamedList))
        map.put(initArgs.getName(i), val.toString());
    }
    if (map.isEmpty())
      return;//short circuit; nothing to do
    SolrParams topInvariants = new MapSolrParams(map);
    // By putting putting the top level into the 1st arg, it overrides request params in 2nd arg.
    req.setParams(SolrParams.wrapDefaults(topInvariants, req.getParams()));
  }

  @Override
  public String getDescription() {
    return "Processes input text to find matching tokens stored in the index.";
  }

  @Override //little value, esp. with git limitations
  public String getSource() {
    return null;
  }

}

/** See LUCENE-4541 or {@link org.apache.solr.response.transform.ValueSourceAugmenter}. */
class ValueSourceAccessor {
  // implement FunctionValues ?
  private final List<AtomicReaderContext> readerContexts;
  private final FunctionValues[] docValuesArr;
  private final ValueSource valueSource;
  private final Map fContext;

  private int localId;
  private FunctionValues values;

  public ValueSourceAccessor(IndexSearcher searcher, ValueSource valueSource) {
    readerContexts = searcher.getIndexReader().leaves();
    this.valueSource = valueSource;
    docValuesArr = new FunctionValues[readerContexts.size()];
    fContext = ValueSource.newContext(searcher);
  }

  private void setState(int docid) {
    int idx = ReaderUtil.subIndex(docid, readerContexts);
    AtomicReaderContext rcontext = readerContexts.get(idx);
    values = docValuesArr[idx];
    if (values == null) {
      try {
        docValuesArr[idx] = values = valueSource.getValues(fContext, rcontext);
      } catch (IOException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      }
    }
    localId = docid - rcontext.docBase;
  }

  public Object objectVal(int docid) {
    setState(docid);
    return values.objectVal(localId);
  }

  //...
}
