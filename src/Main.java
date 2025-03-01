import org.antlr.v4.runtime.*;

import java.io.IOException;

public class Main {
    private static void printToken(Vocabulary vocabulary, Token token) {
        if (token.getType() == SysYLexer.INTEGER_CONST)
            System.err.printf("%s %d at Line %d.\n", vocabulary.getSymbolicName(token.getType()),
                    Integer.decode(token.getText()), token.getLine());
        else System.err.printf("%s %s at Line %d.\n", vocabulary.getSymbolicName(token.getType()), token.getText(),
                token.getLine());
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) System.exit(1);
        var stream = CharStreams.fromFileName(args[0]);
        var lexer = new SysYLexer(stream);
        var vocabulary = lexer.getVocabulary();

        final boolean[] hasError = {false};
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                hasError[0] = true;
                System.err.printf("Error type A at Line %d: %s at char %d.\n", line, msg, charPositionInLine);
            }
        });
        var tokens = lexer.getAllTokens();
        if (!hasError[0])
            tokens.forEach(token -> printToken(vocabulary, token));
    }
}