This is a fix parser that finds the tag and associated position of the value in the byte array, and does some basic validation.

I implemented two versions of parsing, the first loops for the checksum first and then parse from the beginning again, and the second one tries to parse the content while doing checksum. Though it seems the second one saves 1 pass of reading, the result turned out it is slower, possibly because of the complicated if/else and the state transitions when parsing one byte by one byte.

To run it, there are some command line parameter options:
1) (no parameter) : read the file "input.txt" and parse. Output is the filename, message length and number of fields parsed, and each line of the tag and the value. Data type will be printed as hex, and others as string.
2) unittest : run unittes. Output is pass/fail of each case
3) perftest <optional filename>: run perf test, if file is specified, use the file as input to parse, otherwise use the pre-defined message. Output is the message, and the run time of the two methods I implemented.
  For perf test, the conversion of tag (key) is counted while the value is not, since the conversion time varies a lot for different data types, and not all conversions are implemented here.
4) of files (1+): read file one by one, and print the same format of output as 1)

Run time for parsing a message of 203 bytes and 26 fields for 20000 times in nano seconds (measured with System.nanoTime()):
time for test1: 153507600
time for test2: 206494000

