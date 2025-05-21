import org.antlr.v4.runtime.*;
import org.llvm4j.optional.Option;

import java.io.File;
import java.io.IOException;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length != 2) System.exit(1);
        var flag = new boolean[1];
        var parser = getSysYParser(CharStreams.fromFileName(args[0]), flag);
        var checker = new SysYSemanticsChecker();
        var program = parser.program();
        if (flag[0]) return;
        program.accept(checker);
        if (checker.hasError) return;

        var translator = new SysYTranslator();
        program.accept(translator);
        var module = translator.module;
        new LLVMPassManager(module).run();
        module.dump(Option.of(new File(args[1])));
    }

    private static SysYParser getSysYParser(CharStream stream, boolean[] flag) {
        var lexer = new SysYLexer(stream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                System.err.printf("Error type A at Line %d: %s at char %d.\n", line, msg, charPositionInLine);
                flag[0] = true;
            }
        });

        var parser = new SysYParser(new BufferedTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                    int charPositionInLine, String msg, RecognitionException e) {
                System.out.printf("Error type B at Line %d: %s at char %d.\n", line, msg, charPositionInLine);
                flag[0] = true;
            }
        });
        return parser;
    }
}