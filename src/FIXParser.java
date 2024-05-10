/*
Write a FIX parser
The incoming FIX message will be in a byte array
You can assume that the byte array contains one whole FIX message
You must not use any third party libraries
Junit is allowed in your unit test
Make your parser as fast as possible
Do some benchmarks and show the results
Provide an API for people to use your parser.
Design for efficiency.
Efficiency means fast and small memory footprint
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Vector;

public class FIXParser {
    private  static final int loglvl = 3;
    private void debuglog(int lvl, String message) {
        if (loglvl >= lvl)
            System.out.println("__DEBUG: " + message);
    }

    // constants, definitions
    // special delimiters
    private static final byte CHAR_SOH = 0x1;
    private static final byte CHAR_EQUALSIGN = '=';
    private static final byte CHAR_SPACE = ' ';

    enum Tag {
        PossDupFlag         (43     , Type.BOOLEAN                          ),
        MsgType             (35     , Type.STRING                           ),
        NoOrders            (73     , Type.INT      , Property.REPEATED     ),
        OrderID             (37     , Type.INT                              ),
        // data length & data type
        SecureDataLen       (90     , Type.INT      , Property.DATA_LENGTH  ),
        RawDataLen          (95     , Type.INT      , Property.DATA_LENGTH  ),
        XmlDataLen          (212    , Type.INT      , Property.DATA_LENGTH  ),
        SignatureLength     (93     , Type.INT      , Property.DATA_LENGTH  );

        enum Type {
            INT,
            FLOAT,
            CHAR,
            BOOLEAN,
            STRING,
            MULTIPLE_VALUE_STRING,
            TIMESTAMP,
            DATE,
            TIME,
        }
        enum Property {
            NONE,
            REPEATED,
            DATA_LENGTH,
            DATA,
        }

        private Tag(int value, Type type) {
            this.value = value;
            this.type = type;
            this.property = Property.NONE;
        }

        private Tag(int value, Type type, Property property) {
            this.value = value;
            this.type = type;
            this.property = property;
        }
        public final int value;
        public final Type type;
        public final Property property;

        private static final HashMap<Integer, Tag> keymap = new HashMap<>();

        public static Tag getTagType(int tag) {
            return keymap.get(tag);
        }

    }

    // helper class
    public static class FieldIndicator {
        public int tag;
        public int posBegin; // begin of tag, after SOH
        public int posEqualSign; // position of the equal sign
        public int posEnd; // position of the SOH after the value

        public String toString()
        {
            return "tag:" + tag + " -> " + posBegin + ", " + posEqualSign + ", " + posEnd;
        }
    }

    ////////////////////
    /// members
    ////////////////////
    byte[] msg;
    // TODO to exclude <SOH> and "=" in DATA type?
    Vector<Integer> vecSOH = new Vector<>(); // positions of <SOH>, including in data
    Vector<Integer> vecEqualSign = new Vector<>(); // positions of "=", including in data
    Vector<FieldIndicator> vecFields = new Vector<>();

    int curr_pos = 0; // for parsing

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

    // TODO exception when not found?
    private int findNextOf(int pos, byte b) {
        for (int i = pos; i < msg.length; ++i) {
            debuglog(5, "pos:" + i + " char:" + msg[i] + " vs " + b);
            if (msg[i] == b)
                return i;
        }
        return msg.length;
    }

    private boolean prepareAndValidate() {
        interface BytesCompare {
            boolean equal(byte[] b1, int s1, byte[] b2);
        }
        BytesCompare bc = (a1, s, other) -> {
            return Arrays.compare(a1, s, s+other.length, other, 0, other.length) == 0;
        };

        /////// parse header
        // validate BeginString tag key
        var tagBegin = "8=".getBytes();
        if (!bc.equal(msg, 0, tagBegin)) {
            debuglog(1, "invalid message: wrong header tag");
            return false;
        }
        // possibly check protocol support here
        // add soh and equal sign for header
        vecSOH.addElement(-1);

        // validate BodyLength tag key
        int posS = findNextOf(0, CHAR_SOH);
        int posM = findNextOf(posS, CHAR_EQUALSIGN);
        int posE = findNextOf(posM, CHAR_SOH);
        byte[] tagLength = "9=".getBytes();
        if (!bc.equal(msg, posS+1, tagLength)) {
            debuglog(1, "invalid message: wrong length tag");
            return false;
        }
        // store for length comparison
        int idxMsgStart = posE + 1;
        int msglength = parseInt(posM + 1, posE);
        debuglog(3, "length pos: " + posS + "," + posM + "," + posE);

        // validate MsgType tag key
        posS = posE;
        posM = findNextOf(posS, CHAR_EQUALSIGN);
        posE = findNextOf(posM, CHAR_SOH);
        byte[] tagMsgType = "35=".getBytes();
        if (!bc.equal(msg, posS+1, tagMsgType)) {
            debuglog(1, "invalid message: wrong MsgType tag");
            return false;
        }


        // parse trailer
        int idxTrailer = msg.length - 7; // "10=xxx|"
        var tagTrailer = "10=".getBytes();
        if (!bc.equal(msg, idxTrailer, tagTrailer)) {
            debuglog(1, "invalid message: wrong trailing tag");
            return false;
        }

        debuglog(2, "msg length: " + msglength);
        if (msglength != idxTrailer - idxMsgStart) {
            debuglog(1, "invalid message: wrong length");
            return false;
        }

        ///// validate checksum
        byte sum = 0;
        byte checksum;
        checksum = (byte) parseInt(idxTrailer+tagTrailer.length, msg.length-1);
        // parse body & calculate checksum
        for (int idx = 0; idx < idxTrailer; ++idx) {
            sum += msg[idx];
            switch (msg[idx]) {
                case CHAR_SOH: {
                    vecSOH.addElement(idx);
                    break;
                }
                case CHAR_EQUALSIGN: {
                    vecEqualSign.addElement(idx);
                    break;
                }
                default:
                    break;
            }
            debuglog(5, "idx:" + idx + " value:" + String.format("%02x", msg[idx]) + "/" + msg[idx] + " sum:" + sum);
        }

        // TODO remove DEBUG
        {
            for (var i : vecSOH) {
                debuglog(1, "soh:" + i);
            }
            for (var i : vecEqualSign) {
                debuglog(1, "equal:" + i);
            }
        }

        // find checksum value
        debuglog(3, "msg checksum:" + checksum + " calculated:" + sum);
        return checksum == sum;
    }

    private boolean prepareAndValidate2() {
        interface BytesCompare {
            boolean equal(byte[] b1, int s1, byte[] b2);
        }
        BytesCompare bc = (a1, s, other) -> {
            return Arrays.compare(a1, s, s+other.length, other, 0, other.length) == 0;
        };

        /////// parse header
        // validate BeginString tag key
        var tagBegin = "8=".getBytes();
        if (!bc.equal(msg, 0, tagBegin)) {
            debuglog(1, "invalid message: wrong header tag");
            return false;
        }
        // possibly check protocol support here

        // validate BodyLength tag key
        int posS = findNextOf(0, CHAR_SOH);
        int posM = findNextOf(posS, CHAR_EQUALSIGN);
        int posE = findNextOf(posM, CHAR_SOH);
        byte[] tagLength = "9=".getBytes();
        if (!bc.equal(msg, posS+1, tagLength)) {
            debuglog(1, "invalid message: wrong length tag");
            return false;
        }
        // store for length comparison
        int idxMsgStart = posE + 1;
        int msglength = parseInt(posM + 1, posE);
        debuglog(3, "length pos: " + posS + "," + posM + "," + posE);

        // validate MsgType tag key
        posS = posE;
        posM = findNextOf(posS, CHAR_EQUALSIGN);
        posE = findNextOf(posM, CHAR_SOH);
        byte[] tagMsgType = "35=".getBytes();
        if (!bc.equal(msg, posS+1, tagMsgType)) {
            debuglog(1, "invalid message: wrong MsgType tag");
            return false;
        }


        // parse trailer
        int idxTrailer = msg.length - 7; // "10=xxx|"
        var tagTrailer = "10=".getBytes();
        if (!bc.equal(msg, idxTrailer, tagTrailer)) {
            debuglog(1, "invalid message: wrong trailing tag");
            return false;
        }

        debuglog(2, "msg length: " + msglength);
        if (msglength != idxTrailer - idxMsgStart) {
            debuglog(1, "invalid message: wrong length");
            return false;
        }

        ///// validate checksum
        byte sum = 0;
        byte checksum;
        checksum = (byte) parseInt(idxTrailer+tagTrailer.length, msg.length-1);
        // parse body & calculate checksum

        boolean isKey = true;
        boolean isData = false; // this is a data field
        int dataLeft = 0;
        int key = 0;
        FieldIndicator fi = new FieldIndicator();
        fi.posBegin = 0;
        for (int idx = 0; idx < idxTrailer; ++idx) {
            byte b = msg[idx];
            sum += msg[idx];

            // handle
            if (isKey) {
                if (b == CHAR_EQUALSIGN) {
                    isKey = false;
                    fi.tag = key;
                    fi.posEqualSign = idx;
                    key = 0;
                }
                else if (Character.isDigit(b)) {
                    key = key * 10 + b - '0';
                } else {
                    return false;
                }
            } else {
                // value handling
                if (isData) {
                    if (dataLeft > 0)
                        --dataLeft;
                    else
                        isData = false;
                }
                else {
                    if (b == CHAR_SOH) {
                        isKey = true;
                        fi.posEnd = idx;
                        vecFields.addElement(fi);

                        fi = new FieldIndicator();
                        fi.posBegin = idx + 1;
                    }
                }
            }
            debuglog(5, "idx:" + idx + " value:" + String.format("%02x", msg[idx]) + "/" + msg[idx] + " sum:" + sum);
        }

        // add checksum field
        {
            fi = new FieldIndicator();
            fi.tag = 10;
            fi.posBegin = idxTrailer;
            fi.posEqualSign = idxTrailer + 2;
            fi.posEnd = msg.length - 1;
            vecFields.addElement(fi);
        }

        // find checksum value
        debuglog(3, "msg checksum:" + checksum + " calculated:" + sum);
        return checksum == sum;
    }

    private boolean parseMsg() {
        curr_pos = 0;
        int dataLength;
        boolean isDataField = false;
        while (curr_pos < msg.length) {
            // parse tag
            FieldIndicator fi = new FieldIndicator();
            fi.posBegin = curr_pos;
            fi.tag = parseIntInternal(CHAR_EQUALSIGN);
            fi.posEqualSign = curr_pos;
            curr_pos = findNextOf(curr_pos, CHAR_SOH);
            fi.posEnd = curr_pos;
            vecFields.addElement(fi);
            debuglog(1, "key:" + fi.tag + " pos:" + curr_pos);
            debuglog(2, "element: " + fi);
            ++curr_pos; // next of SOH
        }
        return true;
    }

    // posS: inclusive; posE: exclusive
    private int parseInt(int posS, int posE) {
        // avoid extra multiplication when length is 1
        int result = msg[posS] - '0';
        for (int i = posS + 1; i < posE; ++i)
            result = result * 10 + (msg[i]-'0');
        return result;
    }

    private int parseIntInternal(byte terminate) {
        int result = msg[curr_pos++] - '0';
        while (msg[curr_pos] != terminate) {
            result = result * 10 + msg[curr_pos++]-'0';
        }
        return result;
    }

    private String toString(byte[] bytes, Integer start, Integer end)
    {
        return new String(bytes, start, end, StandardCharsets.US_ASCII); // TODO emma: charset UTF_8?
    }

    /////////////////////
    // getter functions
    /////////////////////
    public byte[] getRawMessage(int tag)
    {
        return msg;
    }

    public String getMsg()
    {
        return toString(msg, 0, msg.length);
    }

    ////////////////////
    // testing
    ////////////////////

    public static void mytest() {
        String filename = "fix_neworder4_4.txt";
        FIXParser fp = new FIXParser(filename);
        System.out.println("__DEBUG: message:\n" + fp.getMsg());

        int test = 2;
        if (test == 1) {
            boolean valid = fp.prepareAndValidate();
            if (!valid) {
                System.out.println("Invalid message!");
                return;
            }

            valid = fp.parseMsg();
        }
        else {
            boolean valid = fp.prepareAndValidate2();
            if (!valid) {
                System.out.println("Invalid message!");
                return;
            }
        }

        {
            System.out.println("__DEBUG: size of delimiter:" + fp.vecFields.size());
            System.out.println("__DEBUG: elements in delimiter position:");
            for (Object obj : fp.vecFields) {
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
