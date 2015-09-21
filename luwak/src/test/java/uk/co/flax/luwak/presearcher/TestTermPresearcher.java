package uk.co.flax.luwak.presearcher;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.*;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Weight;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRefHash;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import uk.co.flax.luwak.*;
import uk.co.flax.luwak.matchers.SimpleMatcher;
import uk.co.flax.luwak.queryparsers.LuceneQueryParser;
import uk.co.flax.luwak.termextractor.querytree.QueryTree;

import static uk.co.flax.luwak.assertions.MatchesAssert.assertThat;

/*
 * Copyright (c) 2013 Lemur Consulting Ltd.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class TestTermPresearcher extends PresearcherTestBase {

    @Test
    public void filtersOnTermQueries() throws IOException {

        MonitorQuery query1
                = new MonitorQuery("1", "furble");
        MonitorQuery query2
                = new MonitorQuery("2", "document");
        MonitorQuery query3 = new MonitorQuery("3", "\"a document\"");  // will be selected but not match
        monitor.update(query1, query2, query3);

        InputDocument doc = InputDocument.builder("doc1")
                .addField(TEXTFIELD, "this is a test document", WHITESPACE)
                .build();

        Matches<QueryMatch> matcher = monitor.match(doc, SimpleMatcher.FACTORY);
        assertThat(matcher)
                .hasMatchCount(1)
                .selectedQueries("2", "3")
                .matchesQuery("2")
                .hasQueriesRunCount(2);

    }

    @Test
    public void ignoresTermsOnNotQueries() throws IOException {

        monitor.update(new MonitorQuery("1", "document -test"));

        InputDocument doc1 = InputDocument.builder("doc1")
                .addField(TEXTFIELD, "this is a test document", WHITESPACE)
                .build();

        assertThat(monitor.match(doc1, SimpleMatcher.FACTORY))
                .hasMatchCount(0)
                .hasQueriesRunCount(1);

        InputDocument doc2 = InputDocument.builder("doc2")
                .addField(TEXTFIELD, "weeble sclup test", WHITESPACE)
                .build();

        assertThat(monitor.match(doc2, SimpleMatcher.FACTORY))
                .hasMatchCount(0)
                .hasQueriesRunCount(0);
    }

    @Test
    public void matchesAnyQueries() throws IOException {

        monitor.update(new MonitorQuery("1", "/hell./"));

        InputDocument doc = InputDocument.builder("doc1")
                .addField(TEXTFIELD, "hello", WHITESPACE)
                .build();

        assertThat(monitor.match(doc, SimpleMatcher.FACTORY))
                .hasMatchCount(1)
                .hasQueriesRunCount(1);

    }

    @Override
    protected Presearcher createPresearcher() {
        return new TermFilteredPresearcher();
    }

    @Test
    public void testAnyTermsAreCorrectlyAnalyzed() {

        TermFilteredPresearcher presearcher = new TermFilteredPresearcher();
        QueryTree qt = presearcher.extractor.buildTree(new MatchAllDocsQuery());

        Map<String, BytesRefHash> extractedTerms = presearcher.collectTerms(qt);

        Assertions.assertThat(extractedTerms.size()).isEqualTo(1);

    }

    @Test
    public void testQueryBuilder() throws IOException {

        IndexWriterConfig iwc = new IndexWriterConfig(new KeywordAnalyzer());
        Presearcher presearcher = createPresearcher();

        Directory dir = new RAMDirectory();
        IndexWriter writer = new IndexWriter(dir, iwc);
        try (Monitor monitor = new Monitor(new LuceneQueryParser("f"), presearcher, writer)) {

            monitor.update(new MonitorQuery("1", "f:test"));

            try (IndexReader reader = DirectoryReader.open(writer, false)) {

                IndexReaderContext ctx = reader.getContext();
                InputDocument doc = InputDocument.builder("doc1")
                        .addField("f", "this is a test document", new WhitespaceAnalyzer()).build();

                BooleanQuery q = (BooleanQuery) presearcher.buildQuery(doc, ctx);
                IndexSearcher searcher = new IndexSearcher(ctx);
                Weight w = searcher.createNormalizedWeight(q, true);

                Set<Term> terms = new HashSet<>();
                w.extractTerms(terms);

                Assertions.assertThat(terms).containsOnly(
                        new Term("f", "test"),
                        new Term("__anytokenfield", "__ANYTOKEN__")
                );
            }

        }

    }
}