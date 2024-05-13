import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Vector;

public class FIXParser {
    private static void debuglog(int lvl, String message) {
        final int loglvl = 3;
        if (loglvl >= lvl)
            System.out.println("__DEBUG: " + message);
    }

    // constants, definitions
    // special delimiters
    private static final byte CHAR_SOH = 0x1;
    private static final byte CHAR_EQUALSIGN = '=';
    private static final byte CHAR_SPACE = ' ';

    public enum Tag {
        Unknown             (0      , Type.INT                              ),
        BeginString         (8      , Type.STRING                           ),
        BodyLength          (9      , Type.INT                              ),
        PossDupFlag         (43     , Type.BOOLEAN                          ),
        MsgType             (35     , Type.STRING                           ),
        NoOrders            (73     , Type.INT      , Property.REPEATED     ),
        OrderID             (37     , Type.INT                              ),

        SendingTime         (52     , Type.DATE                             ),

        MessageEncoding     (347    , Type.STRING                           ),
        // text length & text
        EncodedTextLen      (354    , Type.INT      , Property.DATA_LENGTH  ),
        EncodedText         (355    , Type.DATA     , Property.ENCODED      ),

        // data length & data type
        SecureDataLen       (90     , Type.INT      , Property.DATA_LENGTH  ),
        SecureData          (91     , Type.DATA     , Property.DATA         ),
        RawDataLen          (95     , Type.INT      , Property.DATA_LENGTH  ),
        RawData             (96     , Type.DATA     , Property.DATA         ),
        XmlDataLen          (212    , Type.INT      , Property.DATA_LENGTH  ),
        XmlData             (213    , Type.DATA     , Property.DATA         ),
        SignatureLength     (93     , Type.INT      , Property.DATA_LENGTH  ),
        Signature           (89     , Type.DATA     , Property.DATA         ),;

        enum Type {
            INT,
            FLOAT,
            CHAR,
            BOOLEAN,
            DATA,
            STRING,
            TIMESTAMP,
            DATE,
            TIME,
        }
        enum Property {
            NONE,
            REPEATED,
            DATA_LENGTH,
            DATA,
            ENCODED,
            MULTIPLE_VALUE_STRING,
        }

        Tag(int key, Type type) {
            this.key = key;
            this.type = type;
            this.property = Property.NONE;
        }

        Tag(int key, Type type, Property property) {
            this.key = key;
            this.type = type;
            this.property = property;
        }
        public final int key;
        public final Type type;
        public final Property property;

        private static final HashMap<Integer, Tag> keymap = new HashMap<>();

        static {
            for (Tag t : Tag.values()) {
                keymap.put(t.key, t);
            }
        }

        public static Tag getTag(int tag) {
            var t = keymap.get(tag);
            if (t == null)
                return Tag.Unknown;
            return t;
        }

    }

    // helper class
    public static class FieldIndicator {
        public int tagNum;
        public Tag tag;
        public int posTag; // begin of tag, after the last SOH
        public int posValue; // position value, after equal sign
        public int posEnd; // position of the SOH after the value

        public String toString()
        {
            return "tagNum:" + tagNum + " -> " + posTag + ", " + posValue + ", " + posEnd;
        }
    }

    ////////////////////
    /// members
    ////////////////////
    byte[] msg;
    String msgType;
    Charset encoding = StandardCharsets.UTF_8; // useful if encoding is different

    Vector<FieldIndicator> vecFields = new Vector<>();
    HashMap<Integer, FieldIndicator> mapFields = new HashMap<>();

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
            if (msg[i] == b)
                return i;
        }
        return msg.length;
    }

    private int extractInt(int posS, int posE) {
        int sign = 1;
        if (msg[posS] == '-') {
            sign = -1;
            ++posS;
        }
        // avoid extra multiplication when length is 1
        int result = msg[posS++] - '0';
        for (int i = posS; i < posE; ++i) {
            // possible check for digit
            result = result * 10 + (msg[i] - '0');
        }
        return sign * result;
    }

    // returning [int, position next to end of int]
    private int[] extractPositiveIntWithPos(int posS, byte terminate) {
        int sign = 1;
        if (msg[posS] == '-') {
            sign = -1;
            ++posS;
        }
        int result = msg[posS++] - '0';
        while (msg[posS] != terminate) {
            result = result * 10 + msg[posS]-'0';
            ++posS;
        }
        return new int[] {sign * result, posS};
    }

    private String extractString(int start, int end, Charset cs)
    {
        try {
            return new String(msg, start, end - start, cs);
        } catch (UnsupportedCharsetException e) {
            return new String(msg, start, end - start, StandardCharsets.UTF_8);
        }
    }

    private String extractString(int start, int end)
    {
        return extractString(start, end, StandardCharsets.UTF_8);
    }

    private boolean prepareAndValidate() {
        interface BytesCompare {
            boolean equal(byte[] b1, int s1, byte[] b2);
        }
        BytesCompare bc = (a1, s, other) -> {
            return Arrays.compare(a1, s, s + other.length, other, 0, other.length) == 0;
        };

        /////// parse header
        // validate BeginString tag key
        var tagBegin = "8=".getBytes();
        if (!bc.equal(msg, 0, tagBegin)) {
            System.out.println("invalid message: wrong header tag");
            return false;
        }
        // possibly check protocol version support here

        // validate BodyLength tag key
        int posS = findNextOf(0, CHAR_SOH) + 1;
        byte[] tagLength = "9=".getBytes();
        if (!bc.equal(msg, posS, tagLength)) {
            System.out.println("invalid message: wrong length tag");
            return false;
        }
        int[] pLength = extractPositiveIntWithPos(posS + tagLength.length, CHAR_SOH);
        int msgLength = pLength[0];
        int posE = pLength[1];
        int idxMsgStart = posE + 1;
//        debuglog(3, "length pos: " + posS + "," + posE);

        // validate MsgType tag key
        byte[] tagMsgType = "35=".getBytes();
        posS = idxMsgStart;
        posE = findNextOf(posS, CHAR_SOH);
        if (!bc.equal(msg, posS, tagMsgType)) {
            System.out.println("invalid message: wrong MsgType tag");
            return false;
        }
        msgType = extractString(posS + tagMsgType.length, posE);

        /////// parse trailer
        int idxTrailer = msg.length - 7; // "10=xxx|"
        var tagTrailer = "10=".getBytes();
        if (!bc.equal(msg, idxTrailer, tagTrailer)) {
            System.out.println("invalid message: wrong checksum tag");
            return false;
        }

//        debuglog(2, "msg length: " + msgLength);
        if (msgLength != idxTrailer - idxMsgStart) {
            System.out.println("invalid message: message length not match: received:" + msgLength + " actual:" + (idxTrailer-idxMsgStart));
            return false;
        }

        ///// validate checksum
        byte sum = 0;
        byte checksum;
        checksum = (byte) extractInt(idxTrailer+tagTrailer.length, msg.length-1);
        // parse body & calculate checksum
        for (int idx = 0; idx < idxTrailer; ++idx) {
            sum += msg[idx];
//            debuglog(5, "idx:" + idx + " value:" + String.format("%02x", msg[idx]) + "/" + msg[idx] + " sum:" + sum);
        }

        if (checksum != sum) {
            System.out.println("invalid message: checksum mismatch, received:" + checksum + " calculated:" + sum);
            return false;
        }
        return true;
    }

    private boolean parseMsgInternal() {
        int curr_pos = 0;
        int dataLength = 0;
        boolean isDataField = false;
        while (curr_pos < msg.length) {
            // parse tag
            FieldIndicator fi = new FieldIndicator();
            int[] pKey = extractPositiveIntWithPos(curr_pos, CHAR_EQUALSIGN);
            fi.tagNum = pKey[0];
            fi.posTag = curr_pos;
            fi.posValue = pKey[1] + 1;
            curr_pos = pKey[1] + 1;

            Tag t = Tag.getTag(fi.tagNum);

            if (t.property == Tag.Property.DATA_LENGTH) {
                int[] pVal = extractPositiveIntWithPos(curr_pos, CHAR_SOH);
                dataLength = pVal[0];
                fi.posEnd = pVal[1];
                curr_pos = pVal[1];
            }
            else if (t.property == Tag.Property.DATA) {
                curr_pos += dataLength;
                dataLength = 0;
                if (curr_pos >= msg.length) {
                    System.out.println("invalid message, data length overflow");
                    return false;
                }
                if (msg[curr_pos] != CHAR_SOH) {
                    System.out.println("invalid message, SOH not found at the end");
                    return false;
                }
            }
            else
                curr_pos = findNextOf(curr_pos, CHAR_SOH);
            fi.tag = t;

            fi.posEnd = curr_pos;
            vecFields.addElement(fi);
            mapFields.put(fi.tagNum, fi);

//            debuglog(1, "key:" + fi.tag + " pos:" + curr_pos);
//            debuglog(1, "element: " + fi);
            ++curr_pos; // advance to next character of SOH
        }
        return true;
    }

    public boolean parse() {
        boolean valid = prepareAndValidate();
        if (!valid)
            return false;
        valid = parseMsgInternal();
        return valid;
    }

    // attempt to directly parse byte by byte, but not maintained with latest change when I found this is slower
    public boolean parseDirect() {
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
            System.out.println("invalid message: wrong header tag");
            return false;
        }
        // possibly check protocol support here

        // validate BodyLength tag key
        int posS = findNextOf(0, CHAR_SOH);
        int posM = findNextOf(posS, CHAR_EQUALSIGN);
        byte[] tagLength = "9=".getBytes();
        if (!bc.equal(msg, posS+1, tagLength)) {
            System.out.println("invalid message: wrong length tag");
            return false;
        }
        // store for length comparison
        int[] pLength = extractPositiveIntWithPos(posM + 1, CHAR_SOH);
        int msglength = pLength[0];
        int idxMsgStart = pLength[1] + 1;

//        debuglog(3, "length pos: " + posS + "," + posM + ",");

        // validate MsgType tag key
        posS = pLength[1] + 1;
        byte[] tagMsgType = "35=".getBytes();
        int posE = findNextOf(posS, CHAR_SOH);
        if (!bc.equal(msg, posS, tagMsgType)) {
            System.out.println("invalid message: wrong MsgType tag");
            return false;
        }
        msgType = extractString(posS + tagMsgType.length, posE);

        // parse trailer
        int idxTrailer = msg.length - 7; // "10=xxx|"
        var tagTrailer = "10=".getBytes();
        if (!bc.equal(msg, idxTrailer, tagTrailer)) {
            System.out.println("invalid message: wrong trailing tag");
            return false;
        }

//        debuglog(2, "msg length: " + msglength);
        if (msglength != idxTrailer - idxMsgStart) {
            System.out.println("invalid message: wrong length");
            return false;
        }

        ///// validate checksum
        byte sum = 0;
        byte checksum;
        checksum = (byte) extractInt(idxTrailer+tagTrailer.length, msg.length-1);
        // parse body & calculate checksum

        boolean isKey = true;
        boolean isLength = false; // this is a data length field
        boolean isData = false; // this is a data field
        int dataLeft = 0;
        int key = 0;
        FieldIndicator fi = new FieldIndicator();
        fi.posTag = 0;
        for (int idx = 0; idx < idxTrailer; ++idx) {
            byte b = msg[idx];
            sum += msg[idx];

            // handle
            if (isKey) {
                if (b == CHAR_EQUALSIGN) {
                    isKey = false;
                    fi.tagNum = key;
                    fi.posValue = idx + 1;

                    Tag t = Tag.getTag(fi.tagNum);
                    switch (t.property) {
                        case Tag.Property.DATA_LENGTH -> isLength = true;
                        case Tag.Property.DATA -> isData = true;
                    }
                    fi.tag = t;

                    key = 0;
                }
                else if (Character.isDigit(b)) {
                    key = key * 10 + b - '0';
                } else {
                    System.out.println("non-digit found in key: pos:" + idx + ":" + b);
                    return false;
                }
            } else {
                // value handling
                if (isData) {
                    --dataLeft;
                    if (dataLeft == 0) {
                        isData = false;
                        if (msg[idx+1] != CHAR_SOH) {
                            System.out.println("SOH not found at the end of data: " + msg[idx+1]);
                            return false;
                        }
                    }
                }
                else {
                    if (b == CHAR_SOH) {
                        isKey = true;
                        isLength = false;

                        fi.posEnd = idx;
                        vecFields.addElement(fi);
                        mapFields.put(fi.tagNum, fi);

                        fi = new FieldIndicator();
                        fi.posTag = idx + 1;
                    }
                    else if (isLength) {
                        dataLeft = dataLeft * 10 + b - '0';
                    }

                }
            }
//            debuglog(5, "idx:" + idx + " value:" + String.format("%02x", msg[idx]) + "/" + msg[idx] + " sum:" + sum);
        }

        // add checksum field
        {
            fi = new FieldIndicator();
            fi.tagNum = 10;
            fi.tag = Tag.getTag(10);
            fi.posTag = idxTrailer;
            fi.posValue = idxTrailer + 3;
            fi.posEnd = msg.length - 1;
            vecFields.addElement(fi);
        }

        // find checksum value
        if (checksum != sum) {
            System.out.println("invalid message: checksum mismatch, received:" + checksum + " calculated:" + sum);
            return false;
        }
        return true;
    }

    public void print() {
        System.out.println("msg length: " + msg.length + ", number of fields: " + vecFields.size());
        for (var f : vecFields) {
            if (f.tag != null && f.tag.type == Tag.Type.DATA) {
                StringBuilder hex = new StringBuilder();
                for (int i = f.posValue; i < f.posEnd; ++i) {
                    hex.append(String.format("%02x ", msg[i]));
                }
                System.out.println(f.tagNum + " | (bytes in hex) " + hex);
            }
            else if (f.tag != null && f.tag.property == Tag.Property.ENCODED)
                System.out.println(f.tagNum + " | " + new String(msg, f.posValue, f.posEnd-f.posValue, encoding));
            else
                System.out.println(f.tagNum + " | " + new String(msg, f.posValue, f.posEnd-f.posValue));
        }
    }

    public void printDebug()
    {
        System.out.println("msg type: " + msgType);
        System.out.println("size of fields:" + vecFields.size());
        System.out.println("delimiter position in each field:");
        for (Object obj : vecFields) {
            System.out.println(obj.toString());
        }
        System.out.println("tag in fields:");
        mapFields.forEach((key, value) -> System.out.println(key + " -> " + value));
    }

    /////////////////////
    // getter functions
    /////////////////////
    public String getMsgString()
    {
        return extractString(0, msg.length);
    }

    public byte[] getTagValueRaw(int tag) {
        var fi = mapFields.get(tag);
        if (fi == null)
            throw new RuntimeException("tag not exist");
        return Arrays.copyOfRange(msg, fi.posValue, fi.posEnd);
    }

    public int getTagValueInt(int tagNum) {
        var fi = mapFields.get(tagNum);
        if (fi == null)
            throw new RuntimeException("tag not exist");
        else if (fi.tag.type != Tag.Type.INT)
            throw new RuntimeException("tag type not match");

        return extractInt(fi.posValue, fi.posEnd);
    }

    public double getTagValueDouble(int tagNum) {
        var fi = mapFields.get(tagNum);
        if (fi == null)
            throw new RuntimeException("tag not exist");
        else if (fi.tag.type != Tag.Type.INT)
            throw new RuntimeException("tag type not match");

        return Double.parseDouble(extractString(fi.posValue, fi.posEnd));
    }

    public String getTagValueString(int tagNum) {
        var fi = mapFields.get(tagNum);
        if (fi == null)
            throw new RuntimeException("tag not exist");
        else if (fi.tag.type != Tag.Type.STRING)
            throw new RuntimeException("tag type not match");

        if (fi.tag.property == Tag.Property.ENCODED)
            return extractString(fi.posValue, fi.posEnd, encoding);
        else
            return extractString(fi.posValue, fi.posEnd);
    }

    public Date getTagValueDate(int tagNum) {
        var fi = mapFields.get(tagNum);
        if (fi == null)
            throw new RuntimeException("tag not exist");
        else if (fi.tag.type != Tag.Type.DATE)
            throw new RuntimeException("tag type not match");

        String fmt1 = "yyyyMMdd-HH:mm:ss";
        String fmt2 = "yyyyMMdd-HH:mm:ss.SSS";
        SimpleDateFormat sdf;
        if (fi.posEnd - fi.posValue == fmt1.length()) {
            sdf = new SimpleDateFormat(fmt1);
        }
        else {
            sdf = new SimpleDateFormat(fmt2);
        }

        try {
            return sdf.parse(extractString(fi.posValue, fi.posEnd));
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    ////////////////////
    // testing
    ////////////////////
    public static void parseFile(String filename)
    {
        System.out.println("-------- " + filename + " --------");
        FIXParser fp = new FIXParser(filename);
        try {
            fp.parse();
            fp.print();
        } catch (RuntimeException e) {
            System.out.println(e);
        }

    }

    public static void mytest(String filename) {
        byte[] msg;
        try {
            msg = Files.readAllBytes(Paths.get(filename));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        int test = 1;
        {
            FIXParser fp = new FIXParser(msg);
            System.out.println("test1");
            debuglog(1, "message:" +fp.getMsgString());
            boolean success = fp.parse();
            if (!success) {
                System.out.println("Invalid message!");
                // return;
            }

            if (success) {
                fp.print();
                fp.printDebug();
            }
        }
        {
            FIXParser fp = new FIXParser(msg);
            System.out.println("test2");
            debuglog(1, "message:" + fp.getMsgString());
            boolean valid = fp.parseDirect();
            if (!valid) {
                System.out.println("Invalid message!");
            }
            else {
                fp.print();
                fp.printDebug();
            }
        }

    }

    private static void assertEqual(int expected, int actual, String msg) {
        if (expected == actual)
            System.out.println("passed - " + msg);
        else
            System.out.println("failed - " + msg + ", expected:" + expected + ", actual:" + actual);
    }

    private static void assertEqual(double expected, double actual, String msg) {
        if (expected == actual)
            System.out.println("passed - " + msg);
        else
            System.out.println("failed - " + msg + ", expected:" + expected + ", actual:" + actual);
    }

    private static void assertEqual(String expected, String actual, String msg) {
        if (expected.equals(actual))
            System.out.println("passed - " + msg);
        else
            System.out.println("failed - " + msg + ", expected:" + expected + ", actual:" + actual);
    }

    public static void unittest()
    {
        // basic functions
        {
            byte[] msg = "abcde\001defghi\001\00112233\001\002\003".getBytes();
            FIXParser fp = new FIXParser(msg);
            int[] pInt = fp.extractPositiveIntWithPos(14, CHAR_SOH);
            assertEqual(12233, pInt[0], "case 1: basics extractPositiveIntWithPos value");
            assertEqual(19, pInt[1], "case 1: basics extractPositiveIntWithPos end pos");
            assertEqual(5, fp.findNextOf(0, CHAR_SOH), "case 1: basics finxNextOf");
            assertEqual(12233, fp.extractInt(14, 19), "case 1: basics extractInt");
            assertEqual("abcde", fp.extractString(0, 5), "case 1: basics extractString");
        }


        // execution message
        {
            byte[] msg = "8=FIX.4.4\0019=289\00135=8\00134=1090\00149=TESTSELL1\00152=20180920-18:23:53.671\00156=TESTBUY1\0016=113.35\00111=636730640278898634\00114=3500.0000000000\00115=USD\00117=20636730646335310000\00121=2\00131=113.35\00132=3500\00137=20636730646335310000\00138=7000\00139=1\00140=1\00154=1\00155=MSFT\00160=20180920-18:23:53.531\001150=F\001151=3500\001453=1\001448=BRK2\001447=D\001452=1\00110=151\001".getBytes();
            FIXParser fp = new FIXParser(msg);
            fp.parse();

            Date d = fp.getTagValueDate(52);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd-HH:mm:ss.SSS");
            String strDate = sdf.format(d);

            assertEqual(29, fp.vecFields.size(), "case 2: parsed size");
            assertEqual("FIX.4.4", fp.getTagValueString(8), "case 2: BeginString");
            assertEqual("8", fp.getTagValueString(35), "case 2: MsgType");
            assertEqual(289, fp.getTagValueInt(9), "case 2: BodyLength");
            assertEqual(113.35, fp.getTagValueDouble(6), "case 2: AvgPx");
            assertEqual(3500, fp.getTagValueDouble(14), "case 2: CumQty");
            assertEqual("20180920-18:23:53.671", strDate, "case 2: UTCTimestamp");
        }

        // multiple binary data
        {
            byte[] msg = "8=FIX.4.2\0019=56\00135=A\00134=978\00195=12\00196=1\001\002\0010=1\00190=1\00190=12\00191=0=1\001\002\0019=1\001\0021\00110=203\001".getBytes();
            FIXParser fp = new FIXParser(msg);
            fp.parse();
            assertEqual(9, fp.vecFields.size(), "case 3: parsed size");
            assertEqual(20, fp.vecFields.get(3).posTag, "case 3: start of tag message type");
            assertEqual(23, fp.vecFields.get(3).posValue, "case 3: start of value of tag message type");
            assertEqual(26, fp.vecFields.get(3).posEnd, "case 3: end of value of tag message type");
            assertEqual(96, fp.mapFields.get(96).tagNum, "case 3: value of tag raw data");
            assertEqual(96, fp.mapFields.get(96).tag.key, "case 3: tag key of tag raw data");
            assertEqual(33, fp.mapFields.get(96).posTag, "case 3: start of tag raw data");
            assertEqual(36, fp.mapFields.get(96).posValue, "case 3: start of value of tag raw data");
            assertEqual(48, fp.mapFields.get(96).posEnd, "case 3: end of value of tag raw data");
        }
    }

    public static void perftest(String filename)
    {
        byte[] msg = "8=FIX.4.2\0019=180\00135=A\00134=978\00195=12\00196=1\001\002\0010=1\00190=1\00190=12\00191=0=1\001\002\0019=1\001\0021\001552=2\00154=1\001453=2\001448=Party1\001447=D\001452=11\001448=Party2\001447=D\001452=56\00154=2\001453=2\001448=Party3\001447=D\001452=11\001448=Party4\001447=D\001452=56\00110=210\001".getBytes();
        long startTime;
        long endTime;
        long duration;

        startTime = System.nanoTime();
        FIXParser fp;
        if (filename == null)
            fp = new FIXParser(msg);
        else
            fp = new FIXParser(filename);

        System.out.println("msg:" + fp.getMsgString());
        for (int i = 0; i < 20000; ++i)
        {
            fp.parse();
        }
        endTime = System.nanoTime();
        duration = (endTime - startTime);  //divide by 1000000 to get milliseconds.
        System.out.println("time for test1: " + duration);

        startTime = System.nanoTime();
        for (int i = 0; i < 20000; ++i)

        {
            fp.parseDirect();
        }
        endTime = System.nanoTime();
        duration = endTime - startTime;
        System.out.println("time for test2: " + duration);

    }

    public static void main(String[] args) {
        if (args.length == 0) {
            parseFile("input.txt");
        }
        else if (args[0].equals("mytest")) {
            mytest(args[1]);
        }
        else if (args[0].equals("unittest")) {
            unittest();
        }
        else if (args[0].equals("perftest")) {
            String filename;
            if (args.length > 1)
                perftest(args[1]);
            else
                perftest(null);
        }
        else {
            for (var arg : args)
                parseFile(arg);
        }
    }
}
