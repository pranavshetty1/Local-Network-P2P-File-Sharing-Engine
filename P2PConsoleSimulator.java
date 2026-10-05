import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;

/** LAN P2P File Sharing - compact console simulator. Real sockets, 3 virtual peers, one file. */
public class P2PConsoleSimulator {

    static final String GET = "GET|", SIZE = "SIZE|", END = "END";
    static final File BASE = new File(System.getProperty("java.io.tmpdir"), "p2p-console-sim");
    static final List<Peer> peers = new ArrayList<>();
    static final Scanner in = new Scanner(System.in);

    // ---- one peer: a real shared folder + a real ServerSocket on its own port ----
    static class Peer {
        String name, ip = "127.0.0.1"; int port; File dir;

        Peer(String name, int port, Object[][] seed) throws IOException {
            this.name = name; this.port = port;
            dir = new File(BASE, name);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Can't create folder: " + dir);
            if (Objects.requireNonNull(dir.list()).length == 0)
                for (Object[] s : seed) writeFile((String) s[0], (int) s[1]);
            Thread t = new Thread(this::serve);
            t.setDaemon(true);
            t.start();
        }

        void writeFile(String name, int kb) throws IOException {
            byte[] data = new byte[kb * 1024];
            if (name.matches(".*\\.(txt|pdf|docx)$")) {
                byte[] f = "sample content for the P2P demo\n".getBytes();
                for (int i = 0; i < data.length; i++) data[i] = f[i % f.length];
            } else new SecureRandom().nextBytes(data);
            Files.write(new File(dir, new File(name).getName()).toPath(), data);
        }

        File[] files() { return dir.listFiles(File::isFile); }

        File resolve(String name) {
            File f = new File(dir, new File(name).getName());
            try {
                if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) return null;
            } catch (IOException e) { return null; }
            return f.isFile() ? f : null;
        }

        void serve() {
            try (ServerSocket ss = new ServerSocket(port)) {
                while (true) {
                    Thread h = new Thread(new Handler(ss.accept(), this));
                    h.setDaemon(true);
                    h.start();
                }
            } catch (IOException e) { System.out.println("[" + name + "] server error: " + e.getMessage()); }
        }
    }

    // ---- serves one incoming LIST or GET request ----
    static class Handler implements Runnable {
        Socket s; Peer peer;
        Handler(Socket s, Peer peer) { this.s = s; this.peer = peer; }

        public void run() {
            try (Socket sock = s;
                 BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                 OutputStream out = sock.getOutputStream();
                 PrintWriter w = new PrintWriter(out, true)) {
                String req = r.readLine();
                if (req == null) return;
                if (req.equals("LIST")) {
                    for (File f : peer.files()) w.println(f.getName() + "|" + f.length());
                    w.println(END);
                } else if (req.startsWith(GET)) {
                    File f = peer.resolve(req.substring(GET.length()));
                    if (f == null) { w.println(SIZE + "-1"); return; }
                    w.println(SIZE + f.length()); w.flush();
                    try (FileInputStream fis = new FileInputStream(f)) { fis.transferTo(out); }
                }
            } catch (IOException ignored) {}
        }
    }

    // ---- client side: real socket calls to another peer ----
    static List<String> list(Peer p) throws IOException {
        List<String> out = new ArrayList<>();
        try (Socket sock = new Socket(p.ip, p.port);
             PrintWriter w = new PrintWriter(sock.getOutputStream(), true);
             BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream()))) {
            w.println("LIST");
            String line;
            while ((line = r.readLine()) != null && !line.equals(END)) out.add(line);
        }
        return out;
    }

    static long[] download(Peer from, Peer to, String filename) throws IOException {
        long start = System.currentTimeMillis();
        try (Socket sock = new Socket(to.ip, to.port)) {
            PrintWriter w = new PrintWriter(sock.getOutputStream(), true);
            InputStream in = sock.getInputStream();
            w.println(GET + filename);
            String sizeLine = readLine(in); // byte-by-byte: avoids buffering into the file bytes that follow
            long size = Long.parseLong(sizeLine.substring(SIZE.length()));
            if (size < 0) return new long[]{0, 0};
            try (FileOutputStream fos = new FileOutputStream(new File(from.dir, new File(filename).getName()))) {
                long remaining = size; byte[] buf = new byte[8192]; int read;
                while (remaining > 0 && (read = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                    fos.write(buf, 0, read); remaining -= read;
                }
            }
            return new long[]{size, System.currentTimeMillis() - start};
        }
    }

    static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1 && c != '\n') if (c != '\r') b.write(c);
        return b.toString("UTF-8");
    }

    // ---- console menu ----
    public static void main(String[] args) {
        System.out.println("=== LAN P2P File Sharing - Console Simulator ===");
        try {
            peers.add(new Peer("PC-Alpha", 9001, new Object[][]{{"notes.txt", 4}, {"photo.jpg", 300}}));
            peers.add(new Peer("PC-Bravo", 9002, new Object[][]{{"report.pdf", 150}, {"backup.zip", 2000}}));
            peers.add(new Peer("PC-Charlie", 9003, new Object[][]{{"movie.mp4", 5000}, {"resume.docx", 40}}));
        } catch (IOException e) {
            System.out.println("Could not start: " + e.getMessage());
            return;
        }
        System.out.println("Data folder: " + BASE.getAbsolutePath());
        showPeers();
        System.out.println("\nCommands: peers | list <#> | get <from#> <to#> <file> | addfile <#> <name> <kb> | exit");

        while (true) {
            System.out.print("\nsim> ");
            String[] p = in.nextLine().trim().split("\\s+");
            try {
                switch (p[0]) {
                    case "peers": showPeers(); break;
                    case "list": {
                        Peer peer = peers.get(Integer.parseInt(p[1]) - 1);
                        System.out.println("-> " + peer.name + ": LIST");
                        for (String line : list(peer)) System.out.println(peer.name + " -> : " + line);
                        System.out.println(peer.name + " -> : END");
                        break;
                    }
                    case "get": {
                        Peer from = peers.get(Integer.parseInt(p[1]) - 1), to = peers.get(Integer.parseInt(p[2]) - 1);
                        System.out.println(from.name + " -> " + to.name + ": GET|" + p[3]);
                        long[] r = download(from, to, p[3]);
                        if (r[0] == 0) System.out.println(to.name + " -> " + from.name + ": SIZE|-1 (not found)");
                        else System.out.println(to.name + " -> " + from.name + ": SIZE|" + r[0]
                                + "\n" + from.name + ": received " + r[0] + " bytes in " + r[1] + " ms");
                        break;
                    }
                    case "addfile":
                        peers.get(Integer.parseInt(p[1]) - 1).writeFile(p[2], Integer.parseInt(p[3]));
                        System.out.println("Added " + p[2] + " (" + p[3] + " KB)");
                        break;
                    case "exit": System.out.println("Goodbye!"); return;
                    default: System.out.println("Commands: peers | list <#> | get <from#> <to#> <file> | addfile <#> <name> <kb> | exit");
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    static void showPeers() {
        for (int i = 0; i < peers.size(); i++) {
            Peer p = peers.get(i);
            System.out.println((i + 1) + ". " + p.name + " " + p.ip + ":" + p.port);
            for (File f : p.files()) System.out.println("     - " + f.getName() + " (" + f.length() + " bytes)");
        }
    }
}
