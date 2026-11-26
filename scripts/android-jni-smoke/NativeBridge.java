package dev.forgeai.intelligence;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

// Shell-only test runner. Not part of the APK.
public class NativeBridge {
    native long load(String path, int contextSize);
    native void generate(long handle, byte[] prompt, int maxTokens, TokenSink sink);
    native void cancel(long handle);
    native void free(long handle);

    private static byte[] question(String text) {
        return ("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
              + "<|im_start|>user\n" + text + "<|im_end|>\n<|im_start|>assistant\n")
              .getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("LIB MODEL");
        System.load(args[0]);
        NativeBridge bridge = new NativeBridge();
        long handle = bridge.load(args[1], 1024);
        if (handle == 0) throw new AssertionError("Missing native model handle");
        try {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            bridge.generate(handle, question("What is 2 plus 2? Reply with just the number."), 12,
                bytes -> result.write(bytes, 0, bytes.length));
            String answer = new String(result.toByteArray(), StandardCharsets.UTF_8);
            if (!answer.trim().equals("4")) throw new AssertionError("Unexpected response: " + answer);
            System.out.println("PASS: Android JNI loaded real GGUF and streamed answer: " + answer);
            int[] calls = {0};
            bridge.generate(handle, question("Count from one to one hundred, using words."), 64,
                bytes -> { calls[0]++; bridge.cancel(handle); });
            if (calls[0] != 1) throw new AssertionError("Cancel allowed " + calls[0] + " callbacks");
            System.out.println("PASS: Android JNI cancellation stopped after the first callback");
            result.reset();
            bridge.generate(handle, question("What is 2 plus 2? Reply with just the number."), 12,
                bytes -> result.write(bytes, 0, bytes.length));
            if (!new String(result.toByteArray(), StandardCharsets.UTF_8).trim().equals("4"))
                throw new AssertionError("Generation not reusable after cancellation");
        } finally {
            bridge.free(handle);
        }
        System.out.println("PASS: Android JNI loaded, cancelled, reused and freed the model");
    }
}
interface TokenSink { void onBytes(byte[] bytes); }
