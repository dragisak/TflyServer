package com.dragishak;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TCP Server.
 * <p/>
 * Using Java NIO.
 */
public class TflyServer {

    private static final int BUFFER_SIZE = 120;
    private static final int DEFAULT_PORT = 4567;


    /**
     * Writing is done by a separate thread. Requests are queued via BlockingQueue.
     * <p/>
     * I am not worrying about socket buffer overflow for now.
     */
    public static class Writer implements Runnable {

        private final BlockingQueue<Message> queue = new LinkedBlockingQueue<Message>();
        private final Pattern regex = Pattern.compile(".?([a-zA-Z0-9_]+).?");
        private long counter = 0;


        public void write(SocketChannel channel, String text) throws InterruptedException {
            queue.put(new Message(channel, text));
        }


        @Override
        public void run() {
            System.out.println("Writer started");

            try {
                while (true) {
                    Message payload = queue.take(); // will wait until there are messages in queue

                    // Now split
                    Matcher matcher = regex.matcher(payload.getText());
                    if (matcher.find()) {
                        String word = matcher.group();
                        if (word != null) {
                            if (matcher.find()) {
                                String secondWord = matcher.group();
                                if (secondWord != null) {
                                    try {
                                        counter = Long.parseLong(secondWord) + 1;
                                    } catch (NumberFormatException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                            String response = reverse(word.trim()) + " " + Long.toString(counter++) + "\n";
                            try {
                                payload.getChannel().write(ByteBuffer.wrap(response.getBytes()));
                                // TODO: Check if all bytes are written. If not, queue and register selector with OP_WRITE
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }


                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        private String reverse(String input) {
            char[] reversed = new char[input.length()];
            for (int i = 0; i < input.length(); i++) {
                reversed[input.length() - i - 1] = input.charAt(i);
            }
            return new String(reversed);
        }

    }

    /**
     * Server thread just handles selector events.
     * <p/>
     * Data read is just passed to Writer thread.
     */
    public static class Server implements Runnable {

        private final Selector selector;
        private final Writer writer;
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        private final CharsetDecoder decoder = Charset.defaultCharset().newDecoder();

        public Server(int port, Writer writer) throws IOException {
            this.selector = SelectorProvider.provider().openSelector();

            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            serverSocketChannel.configureBlocking(false);
            serverSocketChannel.socket().bind(new InetSocketAddress(port));
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            this.writer = writer;
        }

        @Override
        public void run() {
            System.out.println("Server started");
            // will exit when interrupted
            try {
                while (true) {
                    try {

                        selector.select(); // waits here for at least one key change

                        Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();

                        while (selectedKeys.hasNext()) {
                            SelectionKey key = selectedKeys.next();
                            selectedKeys.remove();

                            if (key.isValid()) {
                                if (key.isAcceptable()) {
                                    acceptConnection(key);
                                } else if (key.isReadable()) {
                                    read(key);
                                }
                            }
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        private void read(SelectionKey key) throws InterruptedException, IOException {
            SocketChannel socketChannel = (SocketChannel) key.channel();
            buffer.clear();
            try {

                int numRead = socketChannel.read(buffer);

                if (numRead == -1) {
                    // EndOfStream reached
                    System.out.println("User closed connection with " + socketChannel.socket().getInetAddress().getHostAddress());
                    key.channel().close();
                    key.cancel();
                } else {
                    buffer.flip();
                    decoder.reset();
                    CharBuffer charBuffer = decoder.decode(buffer);
                    charBuffer.flip();
                    String input = new String(charBuffer.array());
                    if (input.indexOf(4) >= 0) {
                        // EOT, ctrl-D received
                        System.out.println("Closing connection with " + socketChannel.socket().getInetAddress().getHostAddress());
                        key.cancel();
                        socketChannel.close();
                    } else {
                        // Queue for writing
                        writer.write(socketChannel, input);
                    }
                }
            } catch (IOException e) {
                System.out.println("Error reading from channel. Closing connection with " + socketChannel.socket().getInetAddress().getHostAddress());
                key.cancel();
                socketChannel.close();
            }
        }

        private void acceptConnection(SelectionKey key) throws IOException {
            ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
            SocketChannel socketChannel = serverSocketChannel.accept();
            socketChannel.configureBlocking(false);
            System.out.println("Accept connection from " + socketChannel.socket().getInetAddress().getHostAddress());

            // Start waiting for user input
            socketChannel.register(this.selector, SelectionKey.OP_READ);
        }

    }

    public static class Message {
        private final SocketChannel channel;
        private final String text;

        public Message(SocketChannel channel, String text) {
            this.channel = channel;
            this.text = text;
        }

        public SocketChannel getChannel() {
            return channel;
        }


        public String getText() {
            return text;
        }

    }

    public static void main(String[] args) {

        int port = DEFAULT_PORT;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        System.out.println("Starting server on port " + port + " ...");

        try {

            Writer writer = new Writer();
            Thread writerThread = new Thread(writer);
            writerThread.start();

            Server connector = new Server(port, writer);
            Thread serverThread = new Thread(connector);
            serverThread.start();

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
