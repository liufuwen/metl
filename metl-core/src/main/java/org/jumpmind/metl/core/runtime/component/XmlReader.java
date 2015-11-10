/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.metl.core.runtime.component;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jumpmind.exception.IoException;
import org.jumpmind.metl.core.model.Component;
import org.jumpmind.metl.core.runtime.ControlMessage;
import org.jumpmind.metl.core.runtime.LogLevel;
import org.jumpmind.metl.core.runtime.Message;
import org.jumpmind.metl.core.runtime.MisconfiguredException;
import org.jumpmind.metl.core.runtime.flow.ISendMessageCallback;
import org.jumpmind.metl.core.runtime.resource.LocalFile;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

public class XmlReader extends AbstractComponentRuntime {

    public static final String TYPE = "XmlReader";

    public final static String SETTING_GET_FILE_FROM_MESSAGE = "get.file.name.from.message";

    public final static String SETTING_RELATIVE_PATH = "relative.path";

    public final static String SETTING_READ_TAG = "read.tag";

    public final static String SETTING_READ_TAGS_PER_MESSAGE = "read.tags.per.message";

    boolean getFileNameFromMessage = false;

    String relativePathAndFile;

    String readTag;

    int readTagsPerMessage = 1;

    @Override
    protected void start() {
        Component component = getComponent();
        getFileNameFromMessage = component.getBoolean(SETTING_GET_FILE_FROM_MESSAGE, getFileNameFromMessage);
        relativePathAndFile = component.get(SETTING_RELATIVE_PATH, relativePathAndFile);
        readTagsPerMessage = component.getInt(SETTING_READ_TAGS_PER_MESSAGE, readTagsPerMessage);
        readTag = component.get(SETTING_READ_TAG, readTag);
        
        if (!getFileNameFromMessage && component.getResource() == null) {
            throw new MisconfiguredException("A resource has not been selected.  The resource is required if not configured to get the file name from the inbound message");
        }
    }

    @Override
    public void handle(Message inputMessage, ISendMessageCallback callback, boolean unitOfWorkBoundaryReached) {
        List<String> files = getFilesToRead(inputMessage);
        try {
            processFiles(files, callback, unitOfWorkBoundaryReached);
        } catch (Exception e) {
            throw new IoException(e);
        }
    }

    List<String> getFilesToRead(Message inputMessage) {
        ArrayList<String> files = new ArrayList<String>();
        if (getFileNameFromMessage) {
            List<String> fullyQualifiedFiles = inputMessage.getPayload();
            files.addAll(fullyQualifiedFiles);
        } else {
            files.add(relativePathAndFile);
        }
        return files;
    }

    void processFiles(List<String> files, ISendMessageCallback callback, boolean unitOfWorkLastMessage)
            throws XmlPullParserException, IOException {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        ArrayList<String> outboundPayload = new ArrayList<String>();

        int filesProcessed = 0;
        for (String file : files) {            
            log(LogLevel.INFO, "Reading %s", file);
            filesProcessed++;
            Map<String, Serializable> headers = new HashMap<>();
            headers.put("source.file.path", file);
            File xmlFile = null;
            if (!getFileNameFromMessage) {
                String path = getResourceRuntime().getResourceRuntimeSettings().get(LocalFile.LOCALFILE_PATH);
                xmlFile = getFile(path, file);
            } else {
                xmlFile = getFile(file);
            }
            parser.setInput(getFileReader(xmlFile));
            LineNumberReader lineNumberReader = new LineNumberReader(getFileReader(xmlFile));
            lineNumberReader.setLineNumber(1);
            int startCol = 0;
            int startLine = 1;
            int prevEndLine = 1;
            int prevEndCol = 0;
            int eventType = parser.getEventType();
            String line = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                switch (eventType) {
                    case XmlPullParser.START_TAG:
                        if (readTag == null) {
                            readTag = parser.getName();
                            info("Read tag was not set, defaulting to root tag: " + readTag);
                        }
                        if (parser.getName().equals(readTag)) {
                            startCol = prevEndCol;
                            startLine = prevEndLine;
                        }
                        prevEndCol = parser.getColumnNumber();
                        prevEndLine = parser.getLineNumber();
                        break;
                    case XmlPullParser.END_TAG:
                        prevEndCol = parser.getColumnNumber();
                        prevEndLine = parser.getLineNumber();
                        if (parser.getName().equals(readTag)) {
                            StringBuilder xml = new StringBuilder();

                            forward(startLine, lineNumberReader);

                            int linesToRead = parser.getLineNumber() - lineNumberReader.getLineNumber();
                            if (lineNumberReader.getLineNumber() > startLine) {
                                startCol = 0;
                            }
                            line = lineNumberReader.readLine();

                            while (linesToRead >= 0 && line != null) {
                                if (startCol > 0) {
                                    if (line.length() > startCol) {
                                        xml.append(line.substring(startCol)).append("\n");
                                    }
                                    startCol = 0;
                                } else if (linesToRead == 0) {
                                    if (line.length() > parser.getColumnNumber()) {
                                        xml.append(line.substring(0, parser.getColumnNumber()));
                                    } else {
                                        xml.append(line).append("\n");
                                    }
                                } else {
                                    xml.append(line).append("\n");
                                }

                                linesToRead--;
                                if (linesToRead >= 0) {
                                    line = lineNumberReader.readLine();
                                }
                            }
                            getComponentStatistics().incrementNumberEntitiesProcessed(threadNumber);
                            outboundPayload.add(xml.toString());
                            if (outboundPayload.size() == readTagsPerMessage) {
                                callback.sendMessage(headers, outboundPayload, false);
                                outboundPayload = new ArrayList<String>();
                            }
                            startCol = 0;
                        }
                        break;
                }
                eventType = parser.next();
            }

            closeQuietly(lineNumberReader);

            if (outboundPayload.size() > 0) {
                callback.sendMessage(headers, outboundPayload, filesProcessed == files.size() && unitOfWorkLastMessage);
            }
        }

    }

    File getFile(String p) {
        return new File(p);
    }

    File getFile(String p, String f) {
        return new File(p, f);
    }

    FileReader getFileReader(File f) throws FileNotFoundException {
        return new FileReader(f);
    }

    FileReader getFileReader(String f) throws FileNotFoundException {
        return new FileReader(f);
    }

    protected static void forward(int toLine, LineNumberReader lineNumberReader) throws IOException {
        while (lineNumberReader.getLineNumber() < toLine) {
            lineNumberReader.readLine();
        }
    }

    @Override
    public boolean supportsStartupMessages() {
        return true;
    }

}
