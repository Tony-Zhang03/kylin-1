package org.apache.kylin.dict;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.kylin.common.KylinConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.*;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Created by sunyerui on 16/4/28.
 */
public class AppendTrieDictionaryBuilderTest {

    @BeforeClass
    public static void setUp() {
        KylinConfig.destroyInstance();
        System.setProperty(KylinConfig.KYLIN_CONF, "../examples/test_case_data/localmeta");
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setAppendDictBuilderEntrySize(50000);
        config.setAppendDictBuilderCacheSize(2);
        config.setAppendDictCacheSize(3);
        config.setProperty("kylin.hdfs.working.dir", "/tmp");
    }

    @AfterClass
    public static void tearDown() {
        String workingDir = KylinConfig.getInstanceFromEnv().getHdfsWorkingDirectory();
        try {
            FileSystem.get(new Path(workingDir).toUri(), new Configuration()).delete(new Path(workingDir), true);
        } catch (IOException e) {}
    }

    public static final String[] words = new String[]{
        "paint", "par", "part", "parts", "partition", "partitions",
        "party", "partie", "parties", "patient",
        "taste", "tar", "trie", "try", "tries",
        "字典", "字典树", "字母", // non-ascii characters
        "",  // empty
        "paint", "tar", "try", // some dup
    };

    @Test
    public void testStringRepeatly() {
        ArrayList<String> list = new ArrayList<>();
        Collections.addAll(list, words);
        ArrayList<String> notfound = new ArrayList<>();
        notfound.add("pa");
        notfound.add("pars");
        notfound.add("tri");
        notfound.add("字");
        for (int i = 0; i < 100; i++) {
            testStringDictAppend(list, notfound, true);
        }
    }

    @Test
    public void englishWordsTest() throws Exception {
        InputStream is = new FileInputStream("src/test/resources/dict/english-words.80 (scowl-2015.05.18).txt");
        ArrayList<String> str = loadStrings(is);
        testStringDictAppend(str, null, false);
    }

    @Test
    public void categoryNamesTest() throws Exception {
        InputStream is = new FileInputStream("src/test/resources/dict/dw_category_grouping_names.dat");
        ArrayList<String> str = loadStrings(is);
        testStringDictAppend(str, null, true);
    }

    private static ArrayList<String> loadStrings(InputStream is) throws Exception {
        ArrayList<String> r = new ArrayList<String>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        try {
            String word;
            while ((word = reader.readLine()) != null) {
                word = word.trim();
                if (word.isEmpty() == false)
                    r.add(word);
            }
        } finally {
            reader.close();
            is.close();
        }
        return r;
    }

    @Ignore("need huge key set")
    @Test
    public void testHugeKeySet() throws IOException {
        BytesConverter converter = new StringBytesConverter();
        AppendTrieDictionaryBuilder<String> b = new AppendTrieDictionaryBuilder<>(converter, UUID.randomUUID().toString());
        AppendTrieDictionary<String> dict = null;

        InputStream is = new FileInputStream("src/test/resources/dict/huge_key");
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        try {
            String word;
            while ((word = reader.readLine()) != null) {
                word = word.trim();
                if (!word.isEmpty());
                    b.addValue(word);
            }
        } finally {
            reader.close();
            is.close();
        }
        dict = b.build(0);
        dict.dump(System.out);
    }

    private static void testStringDictAppend(ArrayList<String> list, ArrayList<String> notfound, boolean shuffleList) {
        Random rnd = new Random(System.currentTimeMillis());
        ArrayList<String> strList = new ArrayList<String>();
        strList.addAll(list);
        if (shuffleList) {
            Collections.shuffle(strList, rnd);
        }
        BytesConverter converter = new StringBytesConverter();

        AppendTrieDictionaryBuilder<String> b = new AppendTrieDictionaryBuilder<>(converter, UUID.randomUUID().toString());
        AppendTrieDictionary<String> dict = null;
        TreeMap<Integer, String> checkMap = new TreeMap<>();
        int firstAppend = rnd.nextInt(strList.size()/2);
        int secondAppend = firstAppend + rnd.nextInt((strList.size()-firstAppend)/2);
        int appendIndex = 0;
        int checkIndex = 0;

        for (; appendIndex < firstAppend; appendIndex++) {
            b.addValue(strList.get(appendIndex));
        }
        dict = b.build(0);
        dict.dump(System.out);
        for (;checkIndex < firstAppend; checkIndex++) {
            String str = strList.get(checkIndex);
            byte[] bytes = converter.convertToBytes(str);
            int id = dict.getIdFromValueBytesImpl(bytes, 0, bytes.length, 0);
            assertFalse(String.format("Id %d for %s should be empty, but is %s", id, str, checkMap.get(id)),
                    checkMap.containsKey(id) && !str.equals(checkMap.get(id)));
            checkMap.put(id, str);
        }

        // reopen dict and append
        b = dict.rebuildTrieTree();
        for (; appendIndex < secondAppend; appendIndex++) {
            b.addValue(strList.get(appendIndex));
        }
        dict = b.build(0);
        dict.dump(System.out);
        checkIndex = 0;
        for (;checkIndex < secondAppend; checkIndex++) {
            String str = strList.get(checkIndex);
            byte[] bytes = converter.convertToBytes(str);
            int id = dict.getIdFromValueBytesImpl(bytes, 0, bytes.length, 0);
            if (checkIndex < firstAppend) {
                assertEquals("Except id " + id + " for " + str + " but " + checkMap.get(id), str, checkMap.get(id));
            } else {
                // check second append str, should be new id
                assertFalse(String.format("Id %d for %s should be empty, but is %s", id, str, checkMap.get(id)),
                    checkMap.containsKey(id) && !str.equals(checkMap.get(id)));
                checkMap.put(id, str);
            }
        }

        // reopen dict and append rest str
        b = dict.rebuildTrieTree();
        for (; appendIndex < strList.size(); appendIndex++) {
            b.addValue(strList.get(appendIndex));
        }
        dict = b.build(0);
        dict.dump(System.out);
        checkIndex = 0;
        for (; checkIndex < strList.size(); checkIndex++) {
            String str = strList.get(checkIndex);
            byte[] bytes = converter.convertToBytes(str);
            int id = dict.getIdFromValueBytesImpl(bytes, 0, bytes.length, 0);
            if (checkIndex < secondAppend) {
                assertEquals("Except id " + id + " for " + str + " but " + checkMap.get(id), str, checkMap.get(id));
            } else {
                // check third append str, should be new id
                assertFalse(String.format("Id %d for %s should be empty, but is %s", id, str, checkMap.get(id)),
                    checkMap.containsKey(id) && !str.equals(checkMap.get(id)));
                checkMap.put(id, str);
            }
        }
        if (notfound != null) {
            for (String s : notfound) {
                byte[] bytes = converter.convertToBytes(s);
                int id = dict.getIdFromValueBytesImpl(bytes, 0, bytes.length, 0);
                assertEquals(-1, id);
            }
        }

        dict = testSerialize(dict, converter);
        for (String str : strList) {
            byte[] bytes = converter.convertToBytes(str);
            int id = dict.getIdFromValueBytesImpl(bytes, 0, bytes.length, 0);
            assertEquals("Except id " + id + " for " + str + " but " + checkMap.get(id), str, checkMap.get(id));
        }
    }

    private static AppendTrieDictionary<String> testSerialize(AppendTrieDictionary<String> dict, BytesConverter converter) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dataout = new DataOutputStream(bout);
            dict.write(dataout);
            dataout.close();
            ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
            DataInputStream datain = new DataInputStream(bin);
            AppendTrieDictionary<String> r = new AppendTrieDictionary<String>();
            r.readFields(datain);
            datain.close();
            return r;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}