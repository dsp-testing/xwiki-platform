/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.rendering.internal.renderer.chaining;

import java.util.Map;
import java.util.Stack;

import org.apache.commons.lang.StringUtils;
import org.xwiki.rendering.internal.renderer.XWikiMacroPrinter;
import org.xwiki.rendering.internal.renderer.XWikiSyntaxImageRenderer;
import org.xwiki.rendering.internal.renderer.XWikiSyntaxLinkRenderer;
import org.xwiki.rendering.internal.renderer.printer.XWikiSyntaxEscapeWikiPrinter;
import org.xwiki.rendering.listener.Format;
import org.xwiki.rendering.listener.HeaderLevel;
import org.xwiki.rendering.listener.Image;
import org.xwiki.rendering.listener.Link;
import org.xwiki.rendering.listener.LinkType;
import org.xwiki.rendering.listener.ListType;
import org.xwiki.rendering.listener.chaining.EventType;
import org.xwiki.rendering.listener.chaining.ListenerChain;
import org.xwiki.rendering.listener.chaining.StackableChainingListener;
import org.xwiki.rendering.listener.chaining.LookaheadChainingListener.Event;
import org.xwiki.rendering.listener.xml.XMLNode;
import org.xwiki.rendering.renderer.XWikiSyntaxListenerChain;
import org.xwiki.rendering.renderer.chaining.AbstractChainingPrintRenderer;
import org.xwiki.rendering.renderer.printer.DefaultWikiPrinter;
import org.xwiki.rendering.renderer.printer.VoidWikiPrinter;
import org.xwiki.rendering.renderer.printer.WikiPrinter;

/**
 * Convert listener events to XWiki Syntax 2.0 output.
 *  
 * @version $Id$
 * @since 1.8RC1
 */
public class XWikiSyntaxChainingRenderer extends AbstractChainingPrintRenderer implements StackableChainingListener
{
    private XWikiSyntaxLinkRenderer linkRenderer;
    
    private XWikiSyntaxImageRenderer imageRenderer;
    
    private XWikiMacroPrinter macroPrinter;
    
    // Custom States
    
    private boolean isFirstElementRendered = false;

    private StringBuffer listStyle = new StringBuffer();

    private boolean isBeginListItemFound = false;

    private boolean isEndListItemFound = false;

    private boolean isBeginDefinitionListItemFound = false;

    private boolean isEndDefinitionListItemFound = false;

    private boolean isBeginQuotationLineFound = false;

    private boolean isEndQuotationLineFound = false;

    private Stack<Boolean> isEndTableRowFoundStack = new Stack<Boolean>();

    private Map<String, String> previousFormatParameters;
    
    public XWikiSyntaxChainingRenderer(WikiPrinter printer, ListenerChain listenerChain)
    {
        super(new XWikiSyntaxEscapeWikiPrinter(printer, (XWikiSyntaxListenerChain) listenerChain), listenerChain);
        
        this.linkRenderer = new XWikiSyntaxLinkRenderer();
        this.imageRenderer = new XWikiSyntaxImageRenderer();
        this.macroPrinter = new XWikiMacroPrinter();
    }

    /**
     * {@inheritDoc}
     * @see StackableChainingListener#createChainingListenerInstance()
     */
    public StackableChainingListener createChainingListenerInstance()
    {
        return new XWikiSyntaxChainingRenderer(getPrinter(), getListenerChain());
    }

    private XWikiSyntaxListenerChain getXWikiSyntaxListenerChain()
    {
        return (XWikiSyntaxListenerChain) getListenerChain();
    }

    private XWikiSyntaxLinkRenderer getLinkRenderer()
    {
        return this.linkRenderer;
    }

    private XWikiSyntaxImageRenderer getImageRenderer()
    {
        return this.imageRenderer;
    }

    private XWikiMacroPrinter getMacroPrinter()
    {
        return this.macroPrinter;
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#beginDocument()
     */
    @Override
    public void beginDocument()
    {
        // Check if we're starting an embedded document
        if (getXWikiSyntaxListenerChain().getDocumentStateChainingListener().getDocumentDepth() > 1) {
            if (!getXWikiSyntaxListenerChain().getBlockStateChainingListener().isInLine()) {
                printNewLine();
            }

            print("(((");

            // Create a new listener stack in order to preserve current states, to handle the embedded document.
            getListenerChain().pushAllStackableListeners();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#endDocument()
     */
    @Override
    public void endDocument()
    {
        // Ensure that all data in the escape printer have been flushed
        getXWikiPrinter().flush();

        // Check if we're closing an embedded document or not
        if (getXWikiSyntaxListenerChain().getDocumentStateChainingListener().getDocumentDepth() > 1) {
            print(")))");

            // Restore previous listeners that were stacked
            getListenerChain().popAllStackableListeners();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#beginLink(Link, boolean, Map)
     */
    public void beginLink(Link link, boolean isFreeStandingURI, Map<String, String> parameters)
    {
        int linkDepth = getXWikiSyntaxListenerChain().getBlockStateChainingListener().getLinkDepth();

        // If we are at a depth of 2 or greater it means we're in a link inside a link and in this case we 
        // shouldn't output the nested link as a link unless it's a free standing link.
        if (linkDepth < 2) {
            getLinkRenderer().beginRenderLink(getPrinter(), link, isFreeStandingURI, parameters);

            // Defer printing the link content since we need to gather all nested elements
            pushPrinter(new XWikiSyntaxEscapeWikiPrinter(new DefaultWikiPrinter(), getXWikiSyntaxListenerChain()));
        } else if (isFreeStandingURI) {
            print(getLinkRenderer().renderLinkReference(link));
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#endLink(Link, boolean, Map)
     */
    @Override
    public void endLink(Link link, boolean isFreeStandingURI, Map<String, String> parameters)
    {
        // The links in a top level link label are not rendered as link (only the label is printed)
        if (getXWikiSyntaxListenerChain().getBlockStateChainingListener().getLinkDepth() == 1) {
            XWikiSyntaxEscapeWikiPrinter linkBlocksPrinter = getXWikiPrinter();
            linkBlocksPrinter.flush();
            String content = linkBlocksPrinter.toString();
            popPrinter();

            getLinkRenderer().renderLinkContent(getPrinter(), content);
            getLinkRenderer().endRenderLink(getPrinter(), link, isFreeStandingURI, parameters);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see Renderer#beginFormat(Format, Map)
     */
    public void beginFormat(Format format, Map<String, String> parameters)
    {
        switch (format) {
            case BOLD:
                getXWikiPrinter().printBeginBold();
                break;
            case ITALIC:
                getXWikiPrinter().printBeginItalic();
                break;
            case STRIKEDOUT:
                print("--");
                break;
            case UNDERLINED:
                print("__");
                break;
            case SUPERSCRIPT:
                print("^^");
                break;
            case SUBSCRIPT:
                print(",,");
                break;
            case MONOSPACE:
                print("##");
                break;
        }
        // If the previous format had parameters and the parameters are different from the current ones then close them
        if (this.previousFormatParameters != null
            && !this.previousFormatParameters.equals(parameters))
        {
            this.previousFormatParameters = null;
            printParameters(parameters, false);
        } else if (this.previousFormatParameters == null) {
            this.previousFormatParameters = null;
            printParameters(parameters, false);
        } else {
            this.previousFormatParameters = null;
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see Renderer#endFormat(Format, Map)
     */
    @Override
    public void endFormat(Format format, Map<String, String> parameters)
    {
        switch (format) {
            case BOLD:
                print("**");
                break;
            case ITALIC:
                getXWikiPrinter().printEndItalic();
                break;
            case STRIKEDOUT:
                print("--");
                break;
            case UNDERLINED:
                print("__");
                break;
            case SUPERSCRIPT:
                print("^^");
                break;
            case SUBSCRIPT:
                print(",,");
                break;
            case MONOSPACE:
                print("##");
                break;
        }
        if (!parameters.isEmpty()) {
            this.previousFormatParameters = parameters;
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#beginParagraph(java.util.Map)
     */
    @Override
    public void beginParagraph(Map<String, String> parameters)
    {
        printNewLine();
        printParameters(parameters);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#endParagraph(java.util.Map)
     */
    @Override
    public void endParagraph(Map<String, String> parameters)
    {
        this.previousFormatParameters = null;

        // Ensure that any not printed characters are flushed.
        // TODO: Fix this better by introducing a state listener to handle escapes
        getXWikiPrinter().flush();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#onNewLine()
     */
    @Override
    public void onNewLine()
    {
        // - If we're inside a table cell, a paragraph, a list or a section header then if we have already outputted
        // a new line before then this new line should be a line break in order not to break the table cell,
        // paragraph, list or section header.

        // - If the new line is the last element of the paragraph, list or section header then it should be a line break
        // as otherwise it'll be considered as an empty line event next time the generated syntax is read by the XWiki
        // parser.

        if (getXWikiSyntaxListenerChain().getBlockStateChainingListener().isInLine()) {
            if (getXWikiSyntaxListenerChain().getConsecutiveNewLineStateChainingListener().getNewLineCount() > 1) { 
                print("\\\\");
            } else if (getXWikiSyntaxListenerChain().getLookaheadChainingListener().getNextEvent().eventType.isInlineEnd())
            {
                print("\\\\");
            } else {
                print("\n");
            }
        } else {
            print("\n");
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#onMacro(String, java.util.Map, String, boolean)
     */
    @Override
    public void onMacro(String name, Map<String, String> parameters, String content, boolean isInline)
    {
        if (!isInline) {
            printNewLine();
            print(getMacroPrinter().print(name, parameters, content));
        } else {
            getXWikiPrinter().printInlineMacro(getMacroPrinter().print(name, parameters, content));
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#beginHeader(HeaderLevel, Map)
     */
    public void beginHeader(HeaderLevel level, Map<String, String> parameters)
    {
        printNewLine();
        printParameters(parameters);
        print(StringUtils.repeat("=", level.getAsInt()) + " ");
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#endHeader(HeaderLevel, Map)
     */
    @Override
    public void endHeader(HeaderLevel level, Map<String, String> parameters)
    {
        print(" " + StringUtils.repeat("=", level.getAsInt()));
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#onWord(String)
     */
    @Override
    public void onWord(String word)
    {
        printDelayed(word);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#onSpace()
     */
    @Override
    public void onSpace()
    {
        printDelayed(" ");
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#onSpecialSymbol(char)
     */
    @Override
    public void onSpecialSymbol(char symbol)
    {
        printDelayed("" + symbol);
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#beginList(org.xwiki.rendering.listener.ListType, java.util.Map)
     */
    public void beginList(ListType listType, Map<String, String> parameters)
    {
        if (this.isBeginListItemFound && !this.isEndListItemFound) {
            print("\n");
            this.isBeginListItemFound = false;
        } else {
            printNewLine();
        }

        if (listType == ListType.BULLETED) {
            this.listStyle.append("*");
        } else {
            this.listStyle.append("1");
        }
        printParameters(parameters);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#beginListItem()
     */
    @Override
    public void beginListItem()
    {
        if (this.isEndListItemFound) {
            print("\n");
            this.isEndListItemFound = false;
            this.isBeginListItemFound = false;
        }

        this.isBeginListItemFound = true;

        print(this.listStyle.toString());
        if (this.listStyle.charAt(0) == '1') {
            print(".");
        }
        print(" ");
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#endList(org.xwiki.rendering.listener.ListType, java.util.Map)
     */
    @Override
    public void endList(ListType listType, Map<String, String> parameters)
    {
        this.listStyle.setLength(this.listStyle.length() - 1);
        if (getXWikiSyntaxListenerChain().getBlockStateChainingListener().getListDepth() == 1) {
            this.isBeginListItemFound = false;
            this.isEndListItemFound = false;
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.PrintRenderer#endListItem()
     */
    @Override
    public void endListItem()
    {
        this.isEndListItemFound = true;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.Renderer#beginXMLNode(XMLNode)
     */
    @Override
    public void beginXMLNode(XMLNode node)
    {
        // There's no xwiki wiki syntax for writing HTML (we have to use Macros for that). Hence discard
        // any XML node events.
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#endXMLNode(XMLNode)
     */
    @Override
    public void endXMLNode(XMLNode node)
    {
        // There's no xwiki wiki syntax for writing HTML (we have to use Macros for that). Hence discard
        // any XML node events.
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#beginMacroMarker(String, java.util.Map, String, boolean)
     */
    @Override
    public void beginMacroMarker(String name, Map<String, String> parameters, String content, boolean isInline)
    {
        if (!isInline) {
            printNewLine();
        }

        // When we encounter a macro marker we ignore all other blocks inside since we're going to use the macro
        // definition wrapped by the macro marker to construct the xwiki syntax.
        pushPrinter(new XWikiSyntaxEscapeWikiPrinter(VoidWikiPrinter.VOIDWIKIPRINTER, getXWikiSyntaxListenerChain()));
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#endMacroMarker(String, java.util.Map, String, boolean)
     */
    @Override
    public void endMacroMarker(String name, Map<String, String> parameters, String content, boolean isInline)
    {
        this.previousFormatParameters = null;

        popPrinter();

        print(getMacroPrinter().print(name, parameters, content));
    }

    /**
     * {@inheritDoc}
     * 
     * @see PrintRenderer#onId(String)
     */
    @Override
    public void onId(String name)
    {
        print("{{id name=\"" + name + "\"}}");
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.Renderer#onHorizontalLine(Map)
     */
    @Override
    public void onHorizontalLine(Map<String, String> parameters)
    {
        printNewLine();
        printParameters(parameters);
        print("----");
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.Renderer#onVerbatim(String, Map, boolean)
     */
    @Override
    public void onVerbatim(String protectedString, Map<String, String> parameters, boolean isInline)
    {
        if (!isInline) {
            printNewLine();
        }
        printParameters(parameters);
        print("{{{" + protectedString + "}}}");
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.renderer.Renderer#onEmptyLines(int)
     */
    @Override
    public void onEmptyLines(int count)
    {
        print(StringUtils.repeat("\n", count));
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginDefinitionList()
     * @since 1.6M2
     */
    @Override
    public void beginDefinitionList()
    {
        if (this.isBeginListItemFound && !this.isEndListItemFound) {
            print("\n");
            // - we are inside an existing definition list
        } else if (this.isBeginDefinitionListItemFound && !this.isEndDefinitionListItemFound) {
            print("\n");
            this.isBeginDefinitionListItemFound = false;
        } else {
            printNewLine();
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endDefinitionList()
     * @since 1.6M2
     */
    @Override
    public void endDefinitionList()
    {
        if (getXWikiSyntaxListenerChain().getBlockStateChainingListener().getDefinitionListDepth() == 1) {
            this.isBeginDefinitionListItemFound = false;
            this.isEndDefinitionListItemFound = false;
            this.isBeginListItemFound = false;
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginDefinitionTerm()
     * @since 1.6M2
     */
    @Override
    public void beginDefinitionTerm()
    {
        if (this.isEndDefinitionListItemFound) {
            print("\n");
            this.isEndDefinitionListItemFound = false;
            this.isBeginDefinitionListItemFound = false;
        }
        this.isBeginDefinitionListItemFound = true;

        if (this.listStyle.length() > 0) {
            print(this.listStyle.toString());
            if (this.listStyle.charAt(0) == '1') {
                print(".");
            }
        }
        print(StringUtils.repeat(":", getXWikiSyntaxListenerChain().getBlockStateChainingListener().getDefinitionListDepth() - 1));
        print("; ");
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginDefinitionDescription()
     * @since 1.6M2
     */
    @Override
    public void beginDefinitionDescription()
    {
        if (this.isEndDefinitionListItemFound) {
            print("\n");
            this.isEndDefinitionListItemFound = false;
            this.isBeginDefinitionListItemFound = false;
        }
        this.isBeginDefinitionListItemFound = true;

        if (this.listStyle.length() > 0) {
            print(this.listStyle.toString());
            if (this.listStyle.charAt(0) == '1') {
                print(".");
            }
        }
        print(StringUtils.repeat(":", getXWikiSyntaxListenerChain().getBlockStateChainingListener().getDefinitionListDepth() - 1));
        print(": ");
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endDefinitionTerm()
     * @since 1.6M2
     */
    @Override
    public void endDefinitionTerm()
    {
        this.isEndDefinitionListItemFound = true;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endDefinitionDescription()
     * @since 1.6M2
     */
    @Override
    public void endDefinitionDescription()
    {
        this.isEndDefinitionListItemFound = true;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginQuotation(java.util.Map)
     * @since 1.6M2
     */
    @Override
    public void beginQuotation(Map<String, String> parameters)
    {
        if (this.isBeginQuotationLineFound && !this.isEndQuotationLineFound) {
            print("\n");
            this.isBeginQuotationLineFound = false;
        } else {
            printNewLine();
        }

        if (!parameters.isEmpty()) {
            printParameters(parameters);
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endQuotation(java.util.Map)
     * @since 1.6M2
     */
    @Override
    public void endQuotation(Map<String, String> parameters)
    {
        if (getXWikiSyntaxListenerChain().getBlockStateChainingListener().getQuotationDepth() == 1) {
            this.isBeginQuotationLineFound = false;
            this.isEndQuotationLineFound = false;
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginQuotationLine()
     * @since 1.6M2
     */
    @Override
    public void beginQuotationLine()
    {
        if (this.isEndQuotationLineFound) {
            print("\n");
            this.isEndQuotationLineFound = false;
            this.isBeginQuotationLineFound = false;
        }
        this.isBeginQuotationLineFound = true;

        print(StringUtils.repeat(">", getXWikiSyntaxListenerChain().getBlockStateChainingListener().getQuotationDepth()));
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endQuotationLine()
     * @since 1.6M2
     */
    @Override
    public void endQuotationLine()
    {
        this.isEndQuotationLineFound = true;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginTable(java.util.Map)
     */
    @Override
    public void beginTable(Map<String, String> parameters)
    {
        printNewLine();
        if (!parameters.isEmpty()) {
            printParameters(parameters);
        }

        this.isEndTableRowFoundStack.push(false);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginTableCell(java.util.Map)
     */
    @Override
    public void beginTableCell(Map<String, String> parameters)
    {
        print("|");
        printParameters(parameters, false);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginTableHeadCell(java.util.Map)
     */
    @Override
    public void beginTableHeadCell(Map<String, String> parameters)
    {
        print("|=");
        printParameters(parameters, false);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginTableRow(java.util.Map)
     */
    @Override
    public void beginTableRow(Map<String, String> parameters)
    {
        if (this.isEndTableRowFoundStack.peek()) {
            print("\n");
        }

        printParameters(parameters, false);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endTable(java.util.Map)
     */
    @Override
    public void endTable(Map<String, String> parameters)
    {
        this.isEndTableRowFoundStack.pop();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endTableCell(java.util.Map)
     */
    @Override
    public void endTableCell(Map<String, String> parameters)
    {
        this.previousFormatParameters = null;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endTableHeadCell(java.util.Map)
     */
    @Override
    public void endTableHeadCell(Map<String, String> parameters)
    {
        this.previousFormatParameters = null;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endTableRow(java.util.Map)
     */
    @Override
    public void endTableRow(Map<String, String> parameters)
    {
        this.isEndTableRowFoundStack.set(this.isEndTableRowFoundStack.size() - 1, true);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#onImage(org.xwiki.rendering.listener.Image, boolean, Map)
     */
    @Override
    public void onImage(Image image, boolean isFreeStandingURI, Map<String, String> parameters)
    {
        Link link = new Link();
        link.setReference("image:" + getImageRenderer().renderImage(image));
        link.setType(LinkType.URI);

        getLinkRenderer().beginRenderLink(getPrinter(), link, isFreeStandingURI, parameters);
        getLinkRenderer().endRenderLink(getPrinter(), link, isFreeStandingURI, parameters);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#beginError(String, String)
     * @since 1.7M3
     */
    @Override
    public void beginError(String message, String description)
    {
        // Don't do anything since we don't want errors to be visible in XWiki syntax.
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.xwiki.rendering.listener.Listener#endError(String, String)
     * @since 1.7M3
     */
    @Override
    public void endError(String message, String description)
    {
        // Don't do anything since we don't want errors to be visible in XWiki syntax.
    }

    protected void printParameters(Map<String, String> parameters)
    {
        printParameters(parameters, true);
    }

    protected void printParameters(Map<String, String> parameters, boolean newLine)
    {
        if (!parameters.isEmpty()) {
            StringBuffer buffer = new StringBuffer("(%");
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                String value = entry.getValue();
                // Escape quotes in value to not break parameter value syntax
                value = value.replaceAll("[\\\\\"]", "\\\\$0");
                // Escape ending custom parameters syntax
                value = value.replaceAll("\\%\\)", "~%)");
                buffer.append(' ').append(entry.getKey()).append('=').append('\"').append(value).append('\"');
            }
            buffer.append(" %)");

            if (newLine) {
                buffer.append("\n");
            }

            print(buffer.toString());
        }
    }

    private void printDelayed(String text)
    {
        print(text, true);
    }

    private void print(String text)
    {
        print(text, false);
    }

    private void print(String text, boolean isDelayed)
    {
        // Handle empty formatting parameters.
        if (this.previousFormatParameters != null) {
            getPrinter().print("(%%)");
            this.previousFormatParameters = null;
        }

        if (isDelayed) {
            getXWikiPrinter().printDelayed(text);
        } else {
            getPrinter().print(text);
        }
    }

    private void printNewLine()
    {
        if (this.isFirstElementRendered) {
            print("\n\n");
        } else {
            this.isFirstElementRendered = true;
        }
    }

    /**
     * Allows exposing the additional methods of {@link XWikiSyntaxEscapeWikiPrinter}, namely the ability to delay
     * printing some text and the ability to escape characters that would otherwise have a meaning in XWiki syntax.
     */
    public XWikiSyntaxEscapeWikiPrinter getXWikiPrinter()
    {
        return (XWikiSyntaxEscapeWikiPrinter) super.getPrinter();
    }

    @Override
    protected void popPrinter()
    {
        // Ensure that any not printed characters are flushed
        getXWikiPrinter().flush();

        super.popPrinter();
    }

}
