package be.imgn.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class QueryTest {

    @Test
    void buildsNestedQueries() {
        var query = Query.all(Query.term("body", "renard"), Query.not(Query.phrase("title", "le chat")));

        assertThat(query)
                .isEqualTo(new Query.All(List.of(
                        new Query.Term("body", "renard"), new Query.Not(new Query.Phrase("title", "le chat")))));
    }

    @Test
    void cancelsADoubleNegation() {
        var term = Query.term("body", "renard");

        assertThat(Query.not(Query.not(term))).isSameAs(term);
    }

    @Test
    void rejectsAnEmptyAllOrAny() {
        assertThatThrownBy(Query::all).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Query.any(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void copiesTheChildren() {
        var children = new ArrayList<Query>(List.of(Query.term("a", "b")));
        var query = (Query.Any) Query.any(children);
        children.add(Query.term("c", "d"));

        assertThat(query.queries()).hasSize(1);
    }
}
