import java.net.ServerSocket;
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("JVM_SMOKE_READY java=" + System.getProperty("java.version"));
        if (args.length > 0 && args[0].equals("quota")) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream("quota.bin")) {
                byte[] block = new byte[1024 * 1024];
                while (true) out.write(block);
            }
        } else if (args.length > 0 && args[0].equals("memory")) {
            java.util.List<byte[]> blocks = new java.util.ArrayList<byte[]>();
            while (true) blocks.add(new byte[1024 * 1024]);
        } else if (args.length > 0 && args[0].equals("cpu")) {
            while (true) Math.sqrt(System.nanoTime());
        } else {
            ServerSocket server = new ServerSocket(8080);
            while (true) { java.net.Socket socket = server.accept(); socket.close(); }
        }
    }
}
