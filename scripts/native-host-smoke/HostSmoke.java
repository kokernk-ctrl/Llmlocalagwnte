package dev.forgeai.intelligence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Reuses the shell-only Android smoke test's NativeBridge signatures. No APK classes. */
public class HostSmoke {
    static byte[] prompt(String text) {
        return ("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
            + "<|im_start|>user\n" + text + "<|im_end|>\n<|im_start|>assistant\n")
            .getBytes(StandardCharsets.UTF_8);
    }

    static void answer(NativeBridge bridge, long id) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bridge.generate(id, prompt("What is 2 plus 2? Reply with just the number."), 12, bytes::writeBytes);
        String answer = bytes.toString(StandardCharsets.UTF_8).trim();
        if (!answer.equals("4")) throw new AssertionError("Expected 4, got: " + answer);
        System.out.println("PASS host JNI: streamed arithmetic answer: " + answer);
    }

    static void cancellation(NativeBridge bridge, long id) throws InterruptedException {
        CountDownLatch first = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread cancel = new Thread(() -> {
            try {
                if (!first.await(60, TimeUnit.SECONDS)) throw new AssertionError("No first piece");
                bridge.cancel(id);
            } catch (Throwable ex) { error.set(ex); }
            finally { stopped.countDown(); }
        });
        int[] calls = {0};
        cancel.start();
        try {
            bridge.generate(id, prompt("Count from one to one hundred, using words."), 64, piece -> {
                ++calls[0];
                first.countDown();
                try {
                    if (!stopped.await(15, TimeUnit.SECONDS)) throw new AssertionError("Cancel blocked");
                } catch (InterruptedException ex) { throw new AssertionError(ex); }
            });
        } finally {
            first.countDown();
            cancel.join();
        }
        if (error.get() != null || calls[0] != 1) throw new AssertionError("Callbacks: " + calls[0], error.get());
        System.out.println("PASS host JNI: cross-thread cancel after first piece");
    }

    public static void main(String[] args) throws Exception {
        System.load(args[0]);
        NativeBridge bridge = new NativeBridge();
        long id = bridge.load(args[1], 1024);
        try {
            answer(bridge, id);
            cancellation(bridge, id);
            answer(bridge, id);
            RuntimeException marker = new RuntimeException("callback");
            try {
                bridge.generate(id, prompt("Hello!"), 12, b -> { throw marker; });
                throw new AssertionError("Callback exception lost");
            } catch (RuntimeException ex) {
                if (ex != marker) throw ex;
            }
            System.out.println("PASS host JNI: callback exception preserved");
        } finally {
            bridge.free(id);
        }
        try {
            answer(bridge, id);
            throw new AssertionError("Stale handle accepted");
        } catch (IllegalArgumentException expected) {
            System.out.println("PASS host JNI: stale handle rejected");
        }
        long reloaded = bridge.load(args[1], 1024);
        try { answer(bridge, reloaded); }
        finally { bridge.free(reloaded); }
        System.out.println("PASS host JNI: unload and reload");
    }
}
