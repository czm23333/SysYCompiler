import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Main {

    public static void main(String[] args) throws IOException {
        if (args.length == 0) System.exit(1);
        System.out.print(Files.readString(Paths.get(args[0])));
    }
}