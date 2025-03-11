import org.antlr.v4.runtime.*;

import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) System.exit(1);
        var parser = getSysYParser(CharStreams.fromFileName(args[0]));
        var formatter = new SysYFormatVisitor();
        System.out.print(formatter.visit(parser.program()));
    }

    private static SysYParser getSysYParser(CharStream stream) {
        var lexer = new SysYLexer(stream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                System.err.printf("Error type A at Line %d: %s at char %d.\n", line, msg, charPositionInLine);
                System.exit(0);
            }
        });

        var parser = new SysYParser(new BufferedTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                System.err.printf("Error type B at Line %d: %s at char %d.\n", line, msg, charPositionInLine);
                System.exit(0);
            }
        });
        return parser;
    }
}