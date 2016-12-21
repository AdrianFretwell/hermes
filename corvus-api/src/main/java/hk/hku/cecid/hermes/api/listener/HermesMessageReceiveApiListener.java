/* 
 * Copyright(c) 2016 Center for E-Commerce Infrastructure Development, The
 * University of Hong Kong (HKU). All Rights Reserved.
 *
 * This software is licensed under the GNU GENERAL PUBLIC LICENSE Version 2.0 [1]
 * 
 * [1] http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt
 */

package hk.hku.cecid.hermes.api.listener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.xml.soap.AttachmentPart;
import javax.xml.soap.SOAPException;

import org.apache.commons.codec.binary.Base64;

import hk.hku.cecid.ebms.pkg.EbxmlMessage;
import hk.hku.cecid.ebms.spa.EbmsProcessor;
import hk.hku.cecid.ebms.spa.dao.MessageDAO;
import hk.hku.cecid.ebms.spa.dao.MessageDVO;
import hk.hku.cecid.ebms.spa.dao.MessageServerDAO;
import hk.hku.cecid.ebms.spa.dao.PartnershipDAO;
import hk.hku.cecid.ebms.spa.dao.PartnershipDVO;
import hk.hku.cecid.ebms.spa.handler.EbxmlMessageDAOConvertor;
import hk.hku.cecid.ebms.spa.handler.MessageClassifier;
import hk.hku.cecid.ebms.spa.task.MessageValidationException;
import hk.hku.cecid.piazza.commons.dao.DAOException;
import hk.hku.cecid.piazza.commons.json.JsonParseException;
import hk.hku.cecid.piazza.commons.rest.RestRequest;
import hk.hku.cecid.piazza.commons.servlet.RequestListenerException;
import hk.hku.cecid.hermes.api.Constants;
import hk.hku.cecid.hermes.api.ErrorCode;
import hk.hku.cecid.hermes.api.spa.ApiPlugin;


/**
 * HermesMessageReceiveApiListener
 * 
 * @author Patrick Yee
 *
 */
public class HermesMessageReceiveApiListener extends HermesProtocolApiListener {

    public static int MAX_NUMBER = 2147483647;

    protected Map<String, Object> processGetRequest(RestRequest request) throws RequestListenerException {
        HttpServletRequest httpRequest = (HttpServletRequest) request.getSource();
        String protocol = parseFromPathInfo(httpRequest.getPathInfo(), 2).get(1);
        ApiPlugin.core.log.info("Get received message list API invoked, protocol = " + protocol);

        if (!protocol.equalsIgnoreCase(Constants.EBMS_PROTOCOL)) {
            String errorMessage = "Protocol unknown";
            ApiPlugin.core.log.error(errorMessage);
            return createError(ErrorCode.ERROR_PROTOCOL_UNSUPPORTED, errorMessage);
        }

        String partnershipId = httpRequest.getParameter("partnership_id");
        if (partnershipId == null) {
            String errorMessage = "Missing required field: partnership_id";
            ApiPlugin.core.log.error(errorMessage);
            return createError(ErrorCode.ERROR_MISSING_REQUIRED_PARAMETER, errorMessage);
        }

        String includeReadString = httpRequest.getParameter("include_read");
        boolean includeRead = false;
        if (includeReadString != null && includeReadString.equalsIgnoreCase("true")) {
            includeRead = true;
        }

        ApiPlugin.core.log.debug("Parameters: partnership_id=" + partnershipId + ", include_read=" + includeRead);

        try {
            PartnershipDAO partnershipDAO = (PartnershipDAO) EbmsProcessor.core.dao.createDAO(PartnershipDAO.class);
            PartnershipDVO partnershipDVO = (PartnershipDVO) partnershipDAO.createDVO();

            partnershipDVO.setPartnershipId(partnershipId);
            if (!partnershipDAO.retrieve(partnershipDVO)) {
                String errorMessage = "Cannot load partnership: " + partnershipId;
                ApiPlugin.core.log.error(errorMessage);
                return createError(ErrorCode.ERROR_DATA_NOT_FOUND, errorMessage);
            }

            MessageDAO msgDAO = (MessageDAO) EbmsProcessor.core.dao.createDAO(MessageDAO.class);
            MessageDVO criteriaDVO = (MessageDVO) msgDAO.createDVO();
            criteriaDVO.setCpaId(partnershipDVO.getCpaId());
            criteriaDVO.setService(partnershipDVO.getService());
            criteriaDVO.setAction(partnershipDVO.getAction());
            criteriaDVO.setMessageBox(MessageClassifier.MESSAGE_BOX_INBOX);
            if (!includeRead) {
                criteriaDVO.setStatus(MessageClassifier.INTERNAL_STATUS_PROCESSED);
            }
            List results = msgDAO.findMessagesByHistory(criteriaDVO, MAX_NUMBER, 0);

            MessageServerDAO messageServerDao = (MessageServerDAO) EbmsProcessor.core.dao.createDAO(MessageServerDAO.class);

            if (results != null) {
                ArrayList<Object> messages = new ArrayList<Object>();
                for (Iterator i=results.iterator(); i.hasNext() ; ) {
                    MessageDVO message = (MessageDVO) i.next();
                    Map<String, Object> messageDict = new HashMap<String, Object>();
                    messageDict.put("id", message.getMessageId());
                    messageDict.put("timestamp", message.getTimeStamp().getTime() / 1000);
                    messageDict.put("status", message.getStatus());
                    messages.add(messageDict);

                    // save delivered status and clear message from inbox
                    message.setStatus(MessageClassifier.INTERNAL_STATUS_DELIVERED);
                    message.setStatusDescription("Message is delivered");
                    messageServerDao.clearMessage(message);
                }
                Map<String, Object> returnObj = new HashMap<String, Object>();
                returnObj.put("message_ids", messages);
                ApiPlugin.core.log.info("" + messages.size() + " messages returned");
                return returnObj;
            }
            else {
                String errorMessage = "No message can be loaded";
                ApiPlugin.core.log.error(errorMessage);
                return createError(ErrorCode.ERROR_DATA_NOT_FOUND, errorMessage);
            }
        }
        catch (DAOException e) {
            String errorMessage = "Error loading messages";
            ApiPlugin.core.log.error(errorMessage);
            return createError(ErrorCode.ERROR_DATA_NOT_FOUND, errorMessage);
        }
    }

    protected Map<String, Object> processPostRequest(RestRequest request) throws RequestListenerException {
        HttpServletRequest httpRequest = (HttpServletRequest) request.getSource();
        String protocol = parseFromPathInfo(httpRequest.getPathInfo(), 2).get(1);
        ApiPlugin.core.log.info("Get received message API invoked, protocol = " + protocol);

        if (!protocol.equalsIgnoreCase(Constants.EBMS_PROTOCOL)) {
            String errorMessage = "Protocol unknown";
            ApiPlugin.core.log.error(errorMessage);
            return createError(ErrorCode.ERROR_PROTOCOL_UNSUPPORTED, errorMessage);
        }

        Map<String, Object> inputDict = null;
        try {
            inputDict = getDictionaryFromRequest(httpRequest);
        } catch (IOException e) {
            String errorMessage = "Exception while reading input stream";
            ApiPlugin.core.log.error(errorMessage);
            return createError(ErrorCode.ERROR_READING_REQUEST, errorMessage);
        } catch (JsonParseException e) {
            String errorMessage = "Exception while parsing input stream";
            ApiPlugin.core.log.error(errorMessage);
            return createError(ErrorCode.ERROR_PARSING_REQUEST, errorMessage);
        }

        String messageId = null;
        try {
            messageId = (String) inputDict.get("message_id");
            if (messageId == null) {
                String errorMessage = "Missing required field: message_id";
                ApiPlugin.core.log.error(errorMessage);
                return createError(ErrorCode.ERROR_MISSING_REQUIRED_PARAMETER, errorMessage);
            }
        } catch (Exception e) {
            String errorMessage = "Error parsing parameter: message_id";
            ApiPlugin.core.log.error(errorMessage, e);
            return createError(ErrorCode.ERROR_PARSING_REQUEST, errorMessage);
        }

        ApiPlugin.core.log.debug("Parameters: message_id=" + messageId);

        try {
            MessageDAO msgDAO = (MessageDAO) EbmsProcessor.core.dao.createDAO(MessageDAO.class);
            MessageDVO message = (MessageDVO) msgDAO.createDVO();
            message.setMessageId(messageId);
            message.setMessageBox(MessageClassifier.MESSAGE_BOX_INBOX);

            if (msgDAO.findMessage(message)) {
                EbxmlMessage ebxmlMessage = null;
                try {
                    ebxmlMessage = EbxmlMessageDAOConvertor.getEbxmlMessage(message.getMessageId(),
                                                                            MessageClassifier.MESSAGE_BOX_INBOX);
                }
                catch (MessageValidationException e) {
                }

                if (ebxmlMessage == null) {
                    String errorMessage = "Unable to read message from repository";
                    ApiPlugin.core.log.error(errorMessage);
                    return createError(ErrorCode.ERROR_DATA_NOT_FOUND, errorMessage);
                }

                Map<String, Object> returnObj = new HashMap<String, Object>();
                returnObj.put("id", message.getMessageId());
                returnObj.put("cpa_id", message.getCpaId());
                returnObj.put("service", message.getService());
                returnObj.put("action", message.getAction());
                returnObj.put("from_party_id", message.getFromPartyId());
                returnObj.put("to_party_id", message.getToPartyId());
                returnObj.put("conversation_id", message.getConvId());
                returnObj.put("timestamp", message.getTimeStamp().getTime() / 1000);
                returnObj.put("status", message.getStatus());

                try {
                    int numPayload = 0;
                    ArrayList<Object> payloads = new ArrayList<Object>();
                    for (Iterator i=ebxmlMessage.getSOAPMessage().getAttachments(); i.hasNext(); ) {
                        numPayload++;
                        Map<String, Object> payloadDict = new HashMap<String, Object>();
                        AttachmentPart attachmentPart = (AttachmentPart) i.next();
                        payloadDict.put("payload-" + numPayload, getPayload(attachmentPart));
                        payloads.add(payloadDict);
                    }

                    if (numPayload > 0) {
                        returnObj.put("payloads", payloads);
                    }
                }
                catch (Exception e) {
                    String errorMessage = "Error extracting message payload";
                    ApiPlugin.core.log.error(errorMessage, e);
                    return createError(ErrorCode.ERROR_EXTRACTING_PAYLOAD_FROM_MESSAGE, errorMessage);
                }
                return returnObj;
            }
            else {
                String errorMessage = "Message with such id not found";
                ApiPlugin.core.log.error(errorMessage);
                return createError(ErrorCode.ERROR_DATA_NOT_FOUND, errorMessage);
            }
        }
        catch (DAOException e) {
            String errorMessage = "Error loading message status";
            ApiPlugin.core.log.error(errorMessage, e);
            return createError(ErrorCode.ERROR_READING_DATABASE, errorMessage);
        }
    }

    private String getPayload(AttachmentPart attachmentPart) throws SOAPException, IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        InputStream input = attachmentPart.getDataHandler().getInputStream();
        byte [] buffer = new byte[1024];
        int len;
        while ((len = input.read(buffer)) > 0) {
            baos.write(buffer, 0, len);
        }
        byte[] payload = baos.toByteArray();
        input.close();
        baos.close();

        return new String(Base64.encodeBase64(payload));
    }
}
