package org.batfish.question.smt;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.batfish.common.Answerer;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.question.QuestionPlugin;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


public class LocalConsistencyQuestionPlugin extends QuestionPlugin {

    public static class LocalConsistencyAnswerer extends Answerer {

        public LocalConsistencyAnswerer(Question question, IBatfish batfish) {
            super(question, batfish);
        }

        @Override
        public AnswerElement answer() {
            LocalConsistencyQuestion q = (LocalConsistencyQuestion) _question;

            Pattern routerRegex;

            try {
                routerRegex = Pattern.compile(q.getRouterRegex());
            }
            catch (PatternSyntaxException e) {
                throw new BatfishException(String.format(
                        "One of the supplied regexes %s is not a valid java regex.",
                        q.getRouterRegex()), e);
            }

            return _batfish.smtLocalConsistency(routerRegex, q.getStrict(), q.getFullModel());
        }
    }

    public static class LocalConsistencyQuestion extends HeaderQuestion {

        private static final String NODE_REGEX_VAR = "nodeRegex";

        private static final String STRICT_VAR = "strict";

        private String _routerRegex;

        private boolean _strict;

        public LocalConsistencyQuestion() {
            _routerRegex = ".*";
            _strict = false;
        }

        @Override
        public void setJsonParameters(JSONObject parameters) {
            super.setJsonParameters(parameters);

            Iterator<?> paramKeys = parameters.keys();

            while (paramKeys.hasNext()) {
                String paramKey = (String) paramKeys.next();

                if (isBaseKey(paramKey)) {
                    continue;
                }

                try {
                    switch (paramKey) {
                        case NODE_REGEX_VAR:
                            setRouterRegex(parameters.getString(paramKey));
                            break;
                        case STRICT_VAR:
                            setStrict(parameters.getBoolean(paramKey));
                            break;
                        default:
                            throw new BatfishException("Unknown key in "
                                    + getClass().getSimpleName() + ": " + paramKey);
                    }
                }
                catch (JSONException e) {
                    throw new BatfishException("JSONException in parameters", e);
                }
            }
        }

        @JsonProperty(NODE_REGEX_VAR)
        public String getRouterRegex() {
            return _routerRegex;
        }

        @JsonProperty(STRICT_VAR)
        public boolean getStrict() {
            return _strict;
        }

        public void setRouterRegex(String _routerRegex) {
            this._routerRegex = _routerRegex;
        }

        public void setStrict(boolean _strict) {
            this._strict = _strict;
        }

        @Override
        public boolean getDataPlane() {
            return false;
        }

        @Override
        public String getName() {
            return "smt-local-consistency";
        }

        @Override
        public boolean getTraffic() {
            return false;
        }
    }


    @Override
    protected Answerer createAnswerer(Question question, IBatfish batfish) {
        return new LocalConsistencyAnswerer(question, batfish);
    }

    @Override
    protected Question createQuestion() {
        return new LocalConsistencyQuestion();
    }
}
