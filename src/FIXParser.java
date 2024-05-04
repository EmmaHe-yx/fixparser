/*
Write a FIX parser
The incoming FIX message will be in a byte array
You can assume that the byte array contains one whole FIX message
You must not use any third party libraries
Junit is allowed in your unit test
Make your parser as fast as possible
Do some benchmarks and show the results
Provide an APl for people to use your parser.
Design for efficiency.
Efficiency means fast and small memory footprint
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Vector;

public class FIXParser {
    // constants, definitions
    private static final byte DELIMITER = 0x1;

    // helper class
    public static class TagIndicator {
        int posBegin;
        int posEqualSign;
        int posEnd;

        public String toString()
        {
            return "positions: " + posBegin + ", " + posEqualSign + ", " + posEnd;
        }
    }

    ////////////////////
    /// members
    ////////////////////
    byte[] msg;
    Vector<TagIndicator> vDelimiter = new Vector<TagIndicator>();

    ////////////////////
    // functions
    ////////////////////
    public FIXParser(byte[] bytes)
    {
        msg = bytes;
    }
    private int findNextOf(byte[] arr, int pos, byte b) {
        for (int i = pos; i < arr.length; ++i) {
            if (arr[i] == b)
                return i;
        }
        // TODO -1 or length?
        return -1;
    }

    private boolean tokenizeAndValidate()
    {
        boolean valid = true;
        byte sum = 0;
        byte checksum = 0;
        interface Compare {
            boolean compare(byte[] b1, int s, int e, byte[] b2);
        }
        Compare bytesEqual = (a1, s, e, other) -> {
            return Arrays.compare(a1, s, e, other, 0, other.length) == 0;
        };

        // parse header
        int idxHeader = 0;
        var header = "8=".getBytes();
        //if (Arrays.compare(msg, 0, 2, header, 0, 2) == 0) {
        if (bytesEqual.compare(msg, 0, 2, header)) {
        }

        // parse trailer
        int idxTrailer = msg.length - 7; // "10=xxx|"
        var trailer = "10=".getBytes();
        if (Arrays.compare(msg, idxTrailer, idxTrailer+3, trailer, 0, 3) == 0) {
            checksum = (byte) (msg[idxTrailer+3]*100 + msg[idxTrailer+4]*10 + msg[idxTrailer+5]);
        }
        else
            return false;

        // parse body & calculate checksum
        for (int idx = 0; idx < idxTrailer; ++idx) {
            sum += msg[idx];
        }

        // find checksum value
        // TODO compare checksum
        return checksum == sum;
    }

    private boolean parse()
    {
        boolean valid = true;
        return valid;
    }

    private String toString(byte[] bytes, Integer start, Integer end)
    {
        return new String(bytes, start, end, StandardCharsets.US_ASCII); // TODO emma: charset UTF_8?
    }

    public String getMsg()
    {
        return toString(msg, 0, msg.length);
    }


    /////////////////////
    // getter functions
    /////////////////////

    public byte[] getRawMessage(int tag)
    {
        return new byte[0];
    }

    ////////////////////
    // testing
    ////////////////////

    public void mytest() {
        {
            System.out.println("__DEBUG: size of delimiter:" + vDelimiter.size());
            System.out.println("__DEBUG: elements in delimiter position:");
            for (Object obj : vDelimiter) {
                System.out.println("__DEBUG: " + obj);
            }
        }

    }

    public void unittest()
    {
    }

    public void perftest()
    {
    }

    public static void main(String[] args) {
        byte[] b;
        try {
            b = Files.readAllBytes(Paths.get(".\\input.txt"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        FIXParser fp = new FIXParser(b);
        System.out.println("__DEBUG: message:\n" + fp.getMsg());

        boolean valid = fp.tokenizeAndValidate();
        if (!valid) {
            System.out.println("Invalid message!");
            return;
        }

        fp.mytest();

        fp.unittest();
        fp.perftest();
    }
}