package eu.wdaqua.qanary.tagme;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;

import com.google.gson.Gson;

import eu.wdaqua.qanary.commons.QanaryMessage;
import eu.wdaqua.qanary.commons.QanaryQuestion;
import eu.wdaqua.qanary.commons.QanaryUtils;
import eu.wdaqua.qanary.component.QanaryComponent;


@Component
/**
 * This component connected automatically to the Qanary pipeline.
 * The Qanary pipeline endpoint defined in application.properties (spring.boot.admin.url)
 * @see <a href="https://github.com/WDAqua/Qanary/wiki/How-do-I-integrate-a-new-component-in-Qanary%3F" target="_top">Github wiki howto</a>
 */
public class TagmeNED extends QanaryComponent {
    private static final Logger logger = LoggerFactory.getLogger(TagmeNED.class);

    private final String applicationName;
    private final String tagMeServiceURL;
    private final Boolean cacheEnabled;
    private final String cacheFile;

	public TagmeNED(@Value("${spring.application.name}") final String applicationName,
            @Value("${ned-tagme.cache.enabled}") final Boolean cacheEnabled,
            @Value("${ned-tagme.cache.file}") final String cacheFile,
			@Value("${ned-tagme.service.url}") final String tagMeServiceURL
			) {
		this.applicationName = applicationName;
		this.tagMeServiceURL = tagMeServiceURL;
        this.cacheEnabled = cacheEnabled;
        this.cacheFile = cacheFile;
	}

    /**
     * implement this method encapsulating the functionality of your Qanary
     * component
     *
     * @throws Exception
     */
    @Override
    public QanaryMessage process(QanaryMessage myQanaryMessage) throws Exception {
        logger.info("process: {}", myQanaryMessage);

        QanaryUtils myQanaryUtils = this.getUtils(myQanaryMessage);
        QanaryQuestion<String> myQanaryQuestion = new QanaryQuestion<>(myQanaryMessage);
        String myQuestion = myQanaryQuestion.getTextualRepresentation();

        ArrayList<Link> links = new ArrayList<>();

        logger.info("Question {}", myQuestion);
        boolean hasCacheResult = false;
        if (cacheEnabled) {
            FileCacheResult cacheResult = readFromCache(myQuestion);
            hasCacheResult = cacheResult.hasCacheResult;
            links.addAll(cacheResult.links);
        }

        if (!hasCacheResult) {
            logger.info("Question {}", myQuestion);

            String thePath = "";
            thePath = URLEncoder.encode(myQuestion, "UTF-8");
            logger.info("Path {}", thePath);

            HttpClient httpclient = HttpClients.createDefault();
            String serviceUrl = tagMeServiceURL+thePath;
            logger.info("Service call: {}", serviceUrl);
            HttpGet httpget = new HttpGet(serviceUrl);
            //httpget.addHeader("User-Agent", USER_AGENT);
            HttpResponse response = httpclient.execute(httpget);
            try {
                HttpEntity entity = response.getEntity();
                if (entity != null) {
                    InputStream instream = entity.getContent();
                    // String result = getStringFromInputStream(instream);
                    String text = IOUtils.toString(instream, StandardCharsets.UTF_8.name());
                    JSONObject response2 = new JSONObject(text);
                    logger.info("response2: {}", response2);
                    if (response2.has("annotations")) {
                        JSONArray jsonArray = (JSONArray) response2.get("annotations");
                        if (jsonArray.length() != 0) {
                            for (int i = 0; i < jsonArray.length(); i++) {
                                JSONObject explrObject = jsonArray.getJSONObject(i);
                                int begin = (int) explrObject.get("start");
                                int end = (int) explrObject.get("end");
                                double link_probability = explrObject.getDouble("link_probability");
                                logger.info("Question: {}", explrObject);
                                logger.info("Begin: {}", begin);
                                logger.info("end: {}", end);
                                logger.info("link_probability: {}", link_probability);
                                String uri = (String) explrObject.get("title");
                                String finalUri = "http://dbpedia.org/resource/" + uri.replace(" ", "_");
                                //String finalUri1= "http://dbpedia.org/resource/"+uri.replace("%20", "_");
                                logger.info("Here {}", finalUri);
                                //logger.info("Here {}", finalUri1);

                                Link l = new Link();
                                l.begin = begin;
                                l.end = end + 1;
                                l.link = finalUri;
                                if (link_probability >= 0.50) {
                                    logger.info("Adding link_probability >= 0.65 uri {}", finalUri);
                                    links.add(l);
                                }

                            }
                        }
                    }
                }

                if (cacheEnabled) {
                    writeToCache(myQuestion, links);
                }
            } catch (ClientProtocolException e) {
                logger.info("Exception: {}", e);
            } catch (IOException e1) {
                logger.info("Except: {}", e1);
            }
        }

        logger.info("store data in graph {}", myQanaryMessage.getEndpoint());

        for (Link l : links) {
            String sparql = "" //
            		+ "PREFIX qa: <http://www.wdaqua.eu/qa#> " //
                    + "PREFIX oa: <http://www.w3.org/ns/openannotation/core/> " //
                    + "PREFIX xsd: <http://www.w3.org/2001/XMLSchema#> " //
                    + "INSERT { " // 
                    + "GRAPH <" + myQanaryQuestion.getOutGraph() + "> { " //
                    + "  ?a a qa:AnnotationOfInstance . " //
                    + "  ?a oa:hasTarget [ " //
                    + "           a    oa:SpecificResource; " //
                    + "           oa:hasSource    <" + myQanaryQuestion.getUri() + ">; " //
                    + "           oa:hasSelector  [ " //
                    + "                    a oa:TextPositionSelector ; " //
                    + "                    oa:start \"" + l.begin + "\"^^xsd:nonNegativeInteger ; " //
                    + "                    oa:end  \"" + l.end + "\"^^xsd:nonNegativeInteger  " //
                    + "           ] " //
                    + "  ] . " //
                    + "  ?a oa:hasBody <" + l.link + "> ;" //
                    + "     oa:annotatedBy <urn:qanary:" + this.applicationName + "> ; " //
                    + "	    oa:annotatedAt ?time  " + "}} " //
                    + "WHERE { " //
                    + "  BIND (IRI(str(RAND())) AS ?a) ." //
                    + "  BIND (now() as ?time) " //
                    + "}";
            logger.debug("SPARQL query: {}", sparql);
            myQanaryUtils.updateTripleStore(sparql, myQanaryQuestion.getEndpoint().toString()); 
        }
        return myQanaryMessage;
    }

    private FileCacheResult readFromCache(String myQuestion) throws IOException {
        final FileCacheResult cacheResult = new FileCacheResult();
        try {
            File f = ResourceUtils.getFile(cacheFile);
            FileReader fr = new FileReader(f);
            BufferedReader br = new BufferedReader(fr);
            String line;


            while ((line = br.readLine()) != null && !cacheResult.hasCacheResult) {
                String question = line.substring(0, line.indexOf("Answer:"));
                logger.info("{}", line);
                logger.info("{}", myQuestion);

                if (question.trim().equals(myQuestion)) {
                    String answer = line.substring(line.indexOf("Answer:") + "Answer:".length());
                    logger.info("Here {}", answer);
                    answer = answer.trim();
                    JSONArray jsonArr = new JSONArray(answer);
                    if (jsonArr.length() != 0) {
                        for (int i = 0; i < jsonArr.length(); i++) {
                            JSONObject explrObject = jsonArr.getJSONObject(i);

                            logger.info("Question: {}", explrObject);

                            Link l = new Link();
                            l.begin = (int) explrObject.get("begin");
                            l.end = (int) explrObject.get("end") + 1;
                            l.link = explrObject.getString("link");
                            cacheResult.links.add(l);
                        }
                    }
                    cacheResult.hasCacheResult = true;
                    logger.info("hasCacheResult {}", cacheResult.hasCacheResult);

                    break;
                }


            }
            br.close();
        } catch (FileNotFoundException e) {
            //handle this
            logger.info("{}", e);
        }
        return cacheResult;
    }

    private void writeToCache(String myQuestion, ArrayList<Link> links) throws IOException {
        try {
            BufferedWriter buffWriter = new BufferedWriter(new FileWriter("qanary_component-NED-tagme/src/main/resources/questions.txt", true));
            Gson gson = new Gson();

            String json = gson.toJson(links);
            logger.info("gsonwala: {}", json);

            String mainString = myQuestion + " Answer: " + json;
            buffWriter.append(mainString);
            buffWriter.newLine();
            buffWriter.close();
        } catch (FileNotFoundException e) {
            //handle this
            logger.info("{}", e);
        }
    }

    class FileCacheResult {
        public ArrayList<Link> links = new ArrayList<>();
        public boolean hasCacheResult;
    }

    class Link {
        public int begin;
        public int end;
        public String link;
    }
}
