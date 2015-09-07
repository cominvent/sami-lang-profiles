package com.cominvent.langid;

import com.optimaize.langdetect.i18n.LdLocale;
import com.optimaize.langdetect.ngram.NgramExtractors;
import com.optimaize.langdetect.profiles.LanguageProfile;
import com.optimaize.langdetect.profiles.LanguageProfileBuilder;
import com.optimaize.langdetect.profiles.LanguageProfileWriter;
import com.optimaize.langdetect.text.CommonTextObjectFactories;
import com.optimaize.langdetect.text.TextObject;
import com.optimaize.langdetect.text.TextObjectFactory;
import de.l3s.boilerpipe.BoilerpipeProcessingException;
import de.l3s.boilerpipe.extractors.ArticleExtractor;
import org.apache.commons.cli.*;
import org.apache.commons.lang.StringUtils;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;

/**
 * Generates language profile
 */
public class ProfileGenerator {
    private static Logger log = LoggerFactory.getLogger(ProfileGenerator.class);
    private Properties props;
    private HashSet<String> stopwords;

    public ProfileGenerator() {
    }

    public static void main(String[] args) throws ParseException {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel","DEBUG");
        Options options = new Options();
        options.addOption("c", "clean", false, "clean profile cache");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.getArgList().isEmpty()) {
            log.error("No language profiles given as arguments, exiting");
            System.exit(2);
        }

        ProfileGenerator pg = new ProfileGenerator();
        if (cmd.hasOption("c")) {
            pg.clean(cmd.getArgList());
        }
        pg.build(cmd.getArgList());
    }

    public void build(List<String> profiles) {
        for (String profile : profiles) {
            build(profile);
        }
    }

    public void build(String profile) {
        if (!profile.matches("\\w+")) {
            log.error("Profile {} is not a valid profile name, skipping", profile);
            return;
        }
        if (!Arrays.asList(Locale.getISOLanguages()).contains(profile)) {
            log.warn("Profile code {} not a valid ISO-639-1 code", profile);
        }
        File dir = new File(profile);
        if (!dir.exists()) {
            log.info("Profile directory {} does not exist, skipping", profile);
            return;
        }
        try {
            initProperties(profile);
            initStopwords(profile);
        } catch (IOException e) {
            log.error("Failed to init properties for {}",profile, e);
            return;
        }
        String pathPrefix = profile+File.separator;
        File stringsFile = new File(pathPrefix+profile+".strings");
        try {
            if (!stringsFile.exists() || Files.size(stringsFile.toPath()) < 10000) {
                File urlsFile = new File(pathPrefix+profile+".urls");
                if (!urlsFile.exists()) {
                    log.error("Neither strings file or url file exists for profile {}, skipping", profile);
                    return;
                } else {
                    crawl(urlsFile, stringsFile, 3);
                    log.info("Completed crawl for profile {}", profile);
                }
            } else {
                if (stringsFile.exists()) {
                    log.info("Found strings file {}, using instead of re-crawling.", stringsFile.getName());
                }
            }
            if (!stringsFile.exists()) {
                log.error("Could not find or generate {}, skipping.", profile + ".strings");
                return;
            }
        } catch (IOException e) {
            log.error("An IO operation failed while generating profile {}. Reason: {}", profile, e.getMessage());
            return;
        }

        try {
            // Generate profile
            TextObjectFactory textObjectFactory = CommonTextObjectFactories.forIndexingCleanText();
            TextObject inputText = textObjectFactory.create();
            try (BufferedReader br = new BufferedReader(new FileReader(stringsFile))) {
                String line;
                while ((line = br.readLine()) != null) {
                    inputText.append(line.trim());
                }
            } catch (IOException e) {
                log.error("Failed building profile {}, skipping", profile, e);
                return;
            }

            //create the profile:
            LanguageProfile languageProfile = new LanguageProfileBuilder(LdLocale.fromString(profile))
                .ngramExtractor(NgramExtractors.standard())
                .minimalFrequency(5) //adjust please
                .addText(inputText)
                .build();

            new LanguageProfileWriter().writeToDirectory(languageProfile, new File(profile));
        } catch (IOException e) {
            log.error("Failed creating profile for {}", profile, e);
        }
    }

    private void initStopwords(String profile) {
        stopwords = new HashSet<String>();
        try {
            File stopfile = new File(profile+File.separator+"stopwords.txt");
            if (stopfile.exists()) {
                try (BufferedReader br = new BufferedReader(new FileReader(stopfile))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        stopwords.add(line.trim().toLowerCase());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Could not create stopwords for {}", profile, e);
        }
    }

    private void initProperties(String profile) throws IOException {
        props = new Properties();
        File propsFile = new File(profile+File.separator+profile+".properties");
        InputStreamReader isr = new InputStreamReader(new FileInputStream(propsFile), "UTF-8");
        if (propsFile.exists()) {
            props.load(isr);
        }
    }

    public void crawl(File urlsFile, File stringsFile, int maxMb) throws IOException {
        //log.info("Crawling file {}", urlsFile);
        stringsFile.createNewFile();
        String profile = urlsFile.getName().replace("\\.urls", "");
        int size = 0;
        int urls = 0;
        FileWriter writer = new FileWriter(stringsFile);
        try (BufferedReader br = new BufferedReader(new FileReader(urlsFile))) {
            String line;
            while ((line = br.readLine()) != null && size <= maxMb*1024*1024) {
                if (line.startsWith("#") || line.length() == 0) continue;
                try {
                    URL url = new URL(line.trim());
                    urls++;
                    try {
                        String text = fetchText(url).replaceAll("\\s+", " ");
                        text = applyRules(text);
                        writer.write(text+"\n");
                        size += text.length();
                        log.info("Fetched {} bytes from {}", text.length(), url);
                    } catch (Exception e) {
                        log.warn("{}: Failed fetching from URL {}, due to {}, skipping", profile, line, e.getMessage());
//                        e.printStackTrace();
                        writer.write("\n");
                        continue;
                    }
                } catch (MalformedURLException e) {
                    log.warn("{}#{}: Skipping malformed URL {}", profile, urls, line);
                }
            }
            if (size > maxMb*1024*1024) {
                log.info("{}: Aborting crawl, limit of {}Mb text exceeded after {} urls", profile, maxMb, urls);
            } else {
                log.info("{}: Completed crawl of {}Mb text from {} urls", profile, size, urls);
            }
        }
    }

    private String applyRules(String text) {
        text = ruleMustContainChars(text);
        text = ruleMustNotContainChars(text);
        text = removepuct(text);
        text = removeNumbers(text);
        text = removeStop(text);
        return text;
    }

    private String removepuct(String text) {
        String punctRegex = "\\p{Punct}";
        int before = text.length();
        String ret = text.replaceAll(punctRegex, " ");
        log.info("Removed pubctuation with regex {}. Sizes = {}/{}", punctRegex, before, ret.length());
        return ret;
    }

    private String removeStop(String text) {
/*
        Analyzer an = new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String s, Reader reader) {
                TokenStreamComponents comp = new tTokenStreamComponents(new StandardTokenizer(reader));

            }
        };
*/
        String stopRegex = "(?i)\\b(" + StringUtils.join(stopwords, "|") + ")\\b";
        int before = text.length();
        String ret = text.replaceAll(stopRegex, " ");
        log.info("Removed stopwords with regex {}. Sizes = {}/{}", stopRegex, before, ret.length());
        return ret;
    }

    private String getProperty(String prop, String def) {
        String val = getProperty(prop);
        return val==null ? def : val;
    }

    private String removeNumbers(String text) {
        return text.replaceAll("\\b\\d+\\b", " ");
    }

    private String ruleMustContainChars(String text) {
        String chars = getProperty("mustContainChars");//ÁáČčĐđŊŋŠšŦŧŽž";
        log.warn("Applying rule mustContainChars: {}. With chars {} for text {}", chars!=null && containsChars(text, chars), chars, text);
        if (chars == null) return text;
        return containsChars(text, chars) ?
            text :
            "";
    }

    private String ruleMustNotContainChars(String text) {
        String chars = getProperty("mustNotContainChars");//ÁáČčĐđŊŋŠšŦŧŽž";
        log.warn("Applying rule mustNotContainChars: {}. With chars {} for text {}", chars!=null && containsChars(text, chars), chars, text);
        if (chars == null) return text;
        return containsChars(text, chars) ?
            "" :
            text;
    }


    public static boolean containsChars(String str, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            for (int j = 0; j < str.length(); j++) {
                if (c == str.charAt(j)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String getProperty(String prop) {
        return props.getProperty(prop);
    }

    public String fetchText(URL url) throws IOException, TikaException, SAXException, BoilerpipeProcessingException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn.getResponseCode() != 200) {
            throw new IOException("Failed to fetch "+url+", got response "+conn.getResponseCode() + " " + conn.getResponseMessage());
        } else {
            String contenttype = conn.getContentType().split(";")[0];
            log.warn("URL {} has type {}", url, contenttype);

            if (contenttype.startsWith("text/html")) {
                return ArticleExtractor.INSTANCE.getText(url);
            } else {
                Tika tika = new Tika();
                return tika.parseToString(url);
/*

                ContentHandler handler = new BoilerpipeContentHandler(new SAXHandler());
                AutoDetectParser parser = new AutoDetectParser();
                Metadata metadata = new Metadata();
                parser.parse(url.openStream(), handler, metadata);
                return handler.toString();
*/
            }
        }

/*
                HtmlEncodingDetector detector = new HtmlEncodingDetector();
                Metadata metadata = new Metadata();
                TikaInputStream tis = TikaInputStream.get(url, metadata);
*/
    }

    private Properties getPropertiesForProfile(String profile) throws IOException {
        Properties props = new Properties();
        props.load(this.getClass().getClassLoader().getResourceAsStream(profile + File.separator + profile + ".properties"));
        return props;
    }

    public void clean(List<String> argList) {
        for (String profile : argList) {
            try {
                File toDelete = new File(profile+File.separator+profile+".strings");
                Files.delete(toDelete.toPath());
                log.info("Deleted file {}", toDelete.getName());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
