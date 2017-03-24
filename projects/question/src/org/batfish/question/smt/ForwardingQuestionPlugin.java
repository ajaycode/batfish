package org.batfish.question.smt;

import org.batfish.common.Answerer;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.question.QuestionPlugin;
import org.codehaus.jettison.json.JSONObject;

import java.util.Iterator;


public class ForwardingQuestionPlugin extends QuestionPlugin {

    public static class ForwardingAnswerer extends Answerer {

        public ForwardingAnswerer(Question question, IBatfish batfish) {
            super(question, batfish);
        }

        @Override
        public AnswerElement answer() {
            ForwardingQuestion q = (ForwardingQuestion) _question;
            return _batfish.smtForwarding(q.getHeaderSpace(), q.getFailures(), q.getFullModel());
        }
    }

    public static class ForwardingQuestion extends HeaderQuestion {

        @Override
        public void setJsonParameters(JSONObject parameters) {
            super.setJsonParameters(parameters);
            Iterator<?> paramKeys = parameters.keys();
            while (paramKeys.hasNext()) {
                String paramKey = (String) paramKeys.next();
                if (isBaseKey(paramKey)) {
                    continue;
                }
                throw new BatfishException("Unknown key: " + paramKey);
            }
        }

        @Override
        public String getName() {
            return "smt-forwarding";
        }
    }


    @Override
    protected Answerer createAnswerer(Question question, IBatfish batfish) {
        return new ForwardingAnswerer(question, batfish);
    }

    @Override
    protected Question createQuestion() {
        return new ForwardingQuestion();
    }
}
