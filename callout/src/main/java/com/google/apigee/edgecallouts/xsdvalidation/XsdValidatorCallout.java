// XsdValidatorCallout.java
//
// A callout for Apigee Edge that performs a validation of an XML document against an XSD.
//
// Copyright (c) 2015-2016 by Dino Chiesa and Apigee Corporation.
// Copyright (c) 2017-2020 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// Example configuration
//
// <JavaCallout name='Java-Xsd-Validation'>
//   <Properties>
//     <!-- specify the main XSD itself in one of these ways -->
//     <Property name='schema'>file://xslt-filename.xsd</Property> <!-- resource in jar -->
//     <Property name='schema'>http://hostname/url-returning-an-xsd</Property>
//     <Property name='schema'>immediate-string-containing-xsd</Property>
//     <Property name='schema'>{variable-containing-one-of-the-above}</Property>
//
//     <!-- specify any dependent XSD with this syntax -->
//     <Property name='schema:schemalocation.xsd'>one-of-the-above</Property>
//
//     <!-- The document to be validated.  If of type Message, then policy will use x.content -->
//     <Property name='source'>name-of-variable-containing-XML-doc</Property>
//
//   </Properties>
//   <ClassName>com.google.apigee.edgecallouts.xsdvalidation.XsdValidatorCallout</ClassName>
//   <ResourceURL>java://edge-custom-xsd-validation-1.0.4.jar</ResourceURL>
// </JavaCallout>
//

package com.google.apigee.edgecallouts.xsdvalidation;

import com.apigee.flow.execution.ExecutionContext;
import com.apigee.flow.execution.ExecutionResult;
import com.apigee.flow.execution.IOIntensive;
import com.apigee.flow.execution.spi.Execution;
import com.apigee.flow.message.Message;
import com.apigee.flow.message.MessageContext;
import com.google.apigee.edgecallouts.CalloutBase;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

@IOIntensive
public class XsdValidatorCallout extends CalloutBase implements Execution {
  private static final String varPrefix = "xsd_";
  private static final String urlReferencePatternString = "^(https?://)(.+)$";
  private static final Pattern urlReferencePattern = Pattern.compile(urlReferencePatternString);
  private LoadingCache<String, String> fileResourceCache;
  private LoadingCache<String, String> urlResourceCache;

  private static final String EXTERNAL_GENERAL_ENTITIES =
      "http://xml.org/sax/features/external-general-entities";
  private static final String EXTERNAL_PARAMETER_ENTITIES =
      "http://xml.org/sax/features/external-parameter-entities";
  private static final String LOAD_EXTERNAL_DTD =
      "http://apache.org/xml/features/nonvalidating/load-external-dtd";

  public XsdValidatorCallout(Map properties) {
    super(properties);

    fileResourceCache =
        CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .maximumSize(1048000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(
                new CacheLoader<String, String>() {
                  public String load(String key) throws IOException {
                    InputStream in = null;
                    String s = "";
                    try {
                      in = getResourceAsStream(key);
                      byte[] fileBytes = new byte[in.available()];
                      in.read(fileBytes);
                      in.close();
                      s = new String(fileBytes, StandardCharsets.UTF_8);
                    } catch (java.lang.Exception exc1) {
                      // gulp
                    } finally {
                      if (in != null) {
                        in.close();
                      }
                    }
                    return s.trim();
                  }
                });

    urlResourceCache =
        CacheBuilder.newBuilder()
            .concurrencyLevel(4)
            .maximumSize(1048000)
            .expireAfterAccess(10, TimeUnit.MINUTES)
            .build(
                new CacheLoader<String, String>() {
                  public String load(String key) throws IOException {
                    InputStream in = null;
                    String s = "";
                    try {
                      URL url = new URL(key);
                      in = url.openStream();
                      s = new String(readAllBytes(in), StandardCharsets.UTF_8);
                    } catch (java.lang.Exception exc1) {
                      // gulp
                    } finally {
                      if (in != null) {
                        in.close();
                      }
                    }
                    return s.trim();
                  }
                });
  }

  private static byte[] readAllBytes(InputStream in) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    int nRead;
    byte[] data = new byte[2048];
    while ((nRead = in.read(data, 0, data.length)) != -1) {
      buffer.write(data, 0, nRead);
    }
    return buffer.toByteArray();
  }

  public String getVarnamePrefix() {
    return varPrefix;
  }

  private String getSourceProperty() {
    String sourceProp = (String) this.properties.get("source");
    return (sourceProp == null || sourceProp.equals("")) ? "message" : sourceProp;
  }

  private boolean useDomSource() {
    String wantDom = (String) this.properties.get("use-dom-source");
    boolean dom = (wantDom != null) && Boolean.parseBoolean(wantDom);
    return dom;
  }

  private static DOMSource newDomSource(InputStream in)
      throws ParserConfigurationException, SAXException, IOException {
    DocumentBuilderFactory nonThreadSafeFactory = DocumentBuilderFactory.newInstance();
    nonThreadSafeFactory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
    nonThreadSafeFactory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
    nonThreadSafeFactory.setFeature(LOAD_EXTERNAL_DTD, false);
    nonThreadSafeFactory.setNamespaceAware(true);
    DocumentBuilder builder = nonThreadSafeFactory.newDocumentBuilder();
    InputSource inputSource = new InputSource(in);
    Document document = builder.parse(inputSource);
    return new DOMSource(document);
  }

  private Source getSource(MessageContext msgCtxt)
      throws IOException, ParserConfigurationException, SAXException {
    String sourceProp = getSourceProperty();
    Object in = msgCtxt.getVariable(sourceProp);
    if (in == null) {
      throw new IllegalStateException(String.format("source '%s' is empty", sourceProp));
    }
    if (in instanceof com.apigee.flow.message.Message) {
      Message msg = (Message) in;
      if (useDomSource()) {
        return newDomSource(msg.getContentAsStream());
      }
      return new StreamSource(msg.getContentAsStream());
    }

    // assume it resolves to an xml string
    String s = (String) in;
    s = s.trim();
    if (!s.startsWith("<")) {
      throw new IllegalStateException(
          String.format("source '%s' does not appear to be XML", sourceProp));
    }
    InputStream s2 = new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    if (useDomSource()) {
      return newDomSource(s2);
    }
    return new StreamSource(s2);
  }

  private String resolveOneXsd(String xsd, MessageContext msgCtxt)
      throws java.util.concurrent.ExecutionException {
    if (xsd == null || xsd.equals("")) {
      throw new IllegalStateException("configuration error: xsd resolves to null or empty");
    }
    xsd = resolvePropertyValue(xsd, msgCtxt);
    if (xsd == null || xsd.equals("")) {
      throw new IllegalStateException("configuration error: xsd resolves to null or empty");
    }
    xsd = xsd.trim();
    return (xsd.startsWith("<")) ? xsd : maybeResolveUrlReference(xsd);
  }

  private Pair<StreamSource, Map<String, String>> getSchema(MessageContext msgCtxt)
      throws Exception {
    // the schema order is unimportant.

    String mainXsd = (String) this.properties.get("schema");
    if (mainXsd == null || mainXsd.equals("")) {
      throw new IllegalStateException("configuration error: no xsd property");
    }

    Map<String, List<Pair<String, ? extends Object>>> splits =
        this.properties.keySet().stream()
            .filter(key -> key.startsWith("schema:"))
            .map(
                key -> {
                  String[] parts = key.split(":", 2);
                  String xsd = (String) this.properties.get(key);
                  try {
                    return Pair.of(parts[1], resolveOneXsd(xsd, msgCtxt));
                  } catch (Exception exc) {
                    // exc.printStackTrace(System.out);
                    return Pair.of(parts[1], exc);
                  }
                })
            .collect(
                Collectors.groupingBy(
                    x -> (x.right instanceof Exception) ? "exceptions" : "sources"));

    List<Pair<String, ? extends Object>> exceptions = splits.get("exceptions");

    if (exceptions != null && exceptions.size() > 0) {
      throw (Exception) exceptions.get(0).right;
    }

    StreamSource streamSource = new StreamSource(new StringReader(resolveOneXsd(mainXsd, msgCtxt)));
    if (splits.get("sources") != null){
      return Pair.of(streamSource,
                     splits.get("sources").stream()
                     .collect(Collectors.toMap(x -> x.left, x -> (String) x.right)));
    }
    return Pair.of(streamSource, null);
  }

  private static InputStream getResourceAsStream(String resourceName) throws IOException {
    if (!resourceName.startsWith("/")) {
      resourceName = "/" + resourceName;
    }
    InputStream in = XsdValidatorCallout.class.getResourceAsStream(resourceName);
    if (in == null) {
      throw new IOException("resource \"" + resourceName + "\" not found");
    }
    return in;
  }

  private String maybeResolveUrlReference(String ref) throws ExecutionException {
    if (ref.startsWith("file://")) {
      return fileResourceCache.get(ref.substring(7, ref.length()));
    }
    Matcher m = urlReferencePattern.matcher(ref);
    if (m.find()) {
      return urlResourceCache.get(ref);
    }
    return ref;
  }

  public ExecutionResult execute(MessageContext msgCtxt, ExecutionContext exeCtxt) {
    ExecutionResult calloutResult = ExecutionResult.ABORT;
    CustomValidationErrorHandler errorHandler = null;
    boolean debug = getDebug();
    Validator validator = null;
    try {
      SchemaFactory notThreadSafeFactory =
          SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

      // for resolving imports, and also includes
      Pair<StreamSource, Map<String, String>> schemaConfig = getSchema(msgCtxt);
      // if no dependent schema then use the default resolver
      if (schemaConfig.right != null) {
        notThreadSafeFactory.setResourceResolver(new CustomResourceResolver(schemaConfig.right,
                                                                            ref -> urlResourceCache.getUnchecked(ref)));
      }

      Schema schema = notThreadSafeFactory.newSchema(schemaConfig.left);
      validator = schema.newValidator();
      errorHandler = new CustomValidationErrorHandler(msgCtxt, validator, debug);
      validator.setErrorHandler(errorHandler);
      Source source = getSource(msgCtxt);
      validator.validate(source);
      msgCtxt.setVariable(varName("valid"), errorHandler.isValid());
      calloutResult = ExecutionResult.SUCCESS;
    } catch (Exception ex) {
      msgCtxt.setVariable(varName("valid"), false);
      if (debug) {
        String stacktrace = Throwables.getStackTraceAsString(ex);
         System.out.println(stacktrace);  // to MP stdout
        msgCtxt.setVariable(varName("stacktrace"), stacktrace);
      }
      String error = ex.toString();
      msgCtxt.setVariable(varName("exception"), error);
      msgCtxt.setVariable(varName("error"), error);
    } finally {
      if (errorHandler != null) {
        String consolidatedExceptionMessage = errorHandler.getConsolidatedExceptionMessage();
        if (consolidatedExceptionMessage != null) {
          msgCtxt.setVariable(varName("validation_exceptions"), consolidatedExceptionMessage);
        }
        String paths = errorHandler.getPaths();
        if (paths != null) {
          msgCtxt.setVariable(varName("failing_paths"), paths);
        }
      }
    }
    return calloutResult;
  }
}
