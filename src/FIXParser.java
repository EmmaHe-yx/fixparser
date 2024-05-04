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
    private  static final int loglvl = 3;
    private void debuglog(int lvl, String message) {
        if (loglvl >= lvl)
            System.out.println("__DEBUG: " + message);
    }

    // constants, definitions
    private static final byte DELIMITER = 0x1;
    private static final byte EQUALSIGN = '=';


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
    Vector<TagIndicator> vDelimiter = new Vector<>();

    ////////////////////
    // functions
    ////////////////////
    public FIXParser(byte[] bytes)
    {
        msg = bytes;
    }

    public FIXParser(String filename) {
        try {
            msg = Files.readAllBytes(Paths.get(filename));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private int findNextOf(int pos, byte b) {
        for (int i = pos; i < msg.length; ++i) {
            debuglog(5, "pos:" + i + " char:" + msg[i] + " vs " + b);
            if (msg[i] == b)
                return i;
        }
        // TODO -1 or length?
        return msg.length;
    }

    private boolean tokenizeAndValidate()
    {
        byte sum = 0;
        byte checksum;
        interface BytesCompare {
            boolean equal(byte[] b1, int s1, byte[] b2);
        }
        BytesCompare bc = (a1, s, other) -> {
            return Arrays.compare(a1, s, s+other.length, other, 0, other.length) == 0;
        };

        // parse header
        var tagBegin = "8=".getBytes();
        if (!bc.equal(msg, 0, tagBegin)) {
            debuglog(1, "invalid message: wrong header tag");
            return false;
        }

        int posS = findNextOf(0, DELIMITER);
        int posM = findNextOf(posS, EQUALSIGN);
        int posE = findNextOf(posM, DELIMITER);

        byte[] tagLength = "9=".getBytes();
        if (!bc.equal(msg, posS+1, tagLength)) {
            debuglog(1, "invalid message: wrong length tag");
            return false;
        }

        debuglog(3, "length pos: " + posS + "," + posM + "," + posE);

        int idxMsgStart = posE + 1;
        int msglength = parseInt(msg, posM + 1, posE);
        debuglog(2, "msg length: " + msglength);


        // parse trailer
        int idxTrailer = msg.length - 7; // "10=xxx|"
        var tagTrailer = "10=".getBytes();
        if (!bc.equal(msg, idxTrailer, tagTrailer)) {
            debuglog(1, "invalid message: wrong trailing tag");
            return false;
        }

        // compare length
        if (msglength != idxTrailer - idxMsgStart) {
            debuglog(1, "invalid message: wrong length");
            return false;
        }

        checksum = (byte) parseInt(msg, idxTrailer+3, msg.length-1);

        // parse body & calculate checksum
        for (int idx = 0; idx < idxTrailer; ++idx) {
            sum += msg[idx];
            debuglog(5, "idx:" + idx + " value:" + String.format("%02x", msg[idx]) + "/" + msg[idx] + " sum:" + sum);
        }

        // find checksum value
        debuglog(3, "msg checksum:" + checksum + " calculated:" + sum);
        return checksum == sum;
    }

    // posS: inclusive; posE: exclusive
    private int parseInt(byte[] msg, int posS, int posE) {
        int result = 0;
        for (int i = posS; i < posE; ++i)
            result = result * 10 + (msg[i]-'0');
        return result;
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

    public static void mytest() {
        String filename = "example.txt";
        FIXParser fp = new FIXParser(filename);
        System.out.println("__DEBUG: message:\n" + fp.getMsg());

        boolean valid = fp.tokenizeAndValidate();
        if (!valid) {
            System.out.println("Invalid message!");
            return;
        }

        {
            System.out.println("__DEBUG: size of delimiter:" + fp.vDelimiter.size());
            System.out.println("__DEBUG: elements in delimiter position:");
            for (Object obj : fp.vDelimiter) {
                System.out.println("__DEBUG: " + obj);
            }
        }

    }

    public static void unittest()
    {
    }

    public static void perftest()
    {
    }

    public static void main(String[] args) {


        mytest();

        unittest();
        perftest();
    }
}
