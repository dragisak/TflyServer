package com.dragishak;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;
import java.util.Iterator;

/**
 * TCP Server
 */
public class TflyServer implements Runnable {

    private ServerSocketChannel serverSocketChannel;

    private Selector selector;

    // The buffer into which we'll read data when it's available
    private ByteBuffer readBuffer = ByteBuffer.allocate(8192);

    public TflyServer(int port) throws IOException {
        selector = SelectorProvider.provider().openSelector();

        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        serverSocketChannel.socket().bind(new InetSocketAddress(port));
        serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    }

    @Override
    public void run() {
        System.out.println("Started");
        while (true) {
            try {
                selector.select();

                Iterator<SelectionKey> selectedKeys = selector.selectedKeys().iterator();

                while (selectedKeys.hasNext()) {
                    SelectionKey key = selectedKeys.next();
                    selectedKeys.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    // Check what event is available and deal with it
                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void accept(SelectionKey key) throws IOException {
        System.out.print("accept");
        ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
        SocketChannel socketChannel = serverSocketChannel.accept();
        socketChannel.configureBlocking(false);
        System.out.println(" from " + socketChannel.socket().getInetAddress().getHostAddress());

        socketChannel.register(this.selector, SelectionKey.OP_READ);
    }

    private void read(SelectionKey key) throws IOException {
        System.out.println("read");
        SocketChannel socketChannel = (SocketChannel) key.channel();
        // Attempt to read off the channel
        int numRead;
        try {
            numRead = socketChannel.read(this.readBuffer);
        } catch (IOException e) {
            // The remote forcibly closed the connection, cancel
            // the selection key and close the channel.
            key.cancel();
            socketChannel.close();
            return;
        }

        if (numRead == -1) {
            // Remote entity shut the socket down cleanly. Do the
            // same from our end and cancel the channel.
            key.channel().close();
            key.cancel();
            return;
        }


        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void write(SelectionKey key) throws IOException {
        System.out.println("write " + new String(readBuffer.array()));
        SocketChannel socketChannel = (SocketChannel) key.channel();
        socketChannel.write(ByteBuffer.wrap(reverse(readBuffer.array())));
        readBuffer.clear();
        key.interestOps(SelectionKey.OP_READ);
    }

    public static void main(String[] args) {

        int port = 4567;

        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }

        System.out.println("Starting server on port " + port + " ...");

        try {
            TflyServer server = new TflyServer(port);
            Thread serverThread = new Thread(server);
            serverThread.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static byte[] reverse(byte[] input) {
        // reverse the string
        byte[] reversed = new byte[input.length];
        for (int i = 0; i < input.length; i++) {
            reversed[input.length - i -1] = input[i];
        }

        return reversed;
    }
}
