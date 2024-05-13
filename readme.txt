This is a fix parser that finds the tag and associated position of the value in the byte array, and does some basic validation.
I implemented two versions of parsing, the first checks for the checksum first and then parse from the beginning, and the second one tries to parse the content while doing checksum. Though the second one saves 1 pass of reading, the result turned out it is not necessarily faster, possibly because of the complicated if/else and the state transitions when parsing one by one.

run time for parsing a message of 203 bytes and 26 fields for 20000 times in nano seconds:
time for test1: 153507600
time for test2: 206494000
