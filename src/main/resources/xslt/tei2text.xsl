<?xml version="1.0"?>
<xsl:stylesheet version="2.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:tei="http://www.tei-c.org/ns/1.0">

    <xsl:output method="text" omit-xml-declaration="yes" />
    <xsl:strip-space elements="*" />

    <!--
        'false' (default): the text of one paragraph-level element, exactly as
        it has always been - Lucene, grep, quote fragments and the vectors'
        text_sha256 are computed from it, so the default-mode templates
        below must not change.

        'true': a whole division as a document (the .txt export, DAV, MCP).
        Every block element ends its line, headings and divisions are
        followed by a blank line, table cells are space-separated. The
        default mode separated blocks only from following siblings, so text
        ran together across divisions and around elements it had no template
        for: "LORD BACONA GARDENGOD almighty", "CHAPTER 1CHAPTER 2".
        The transformer is reused per thread and keeps its parameters:
        callers always pass it.
    -->
    <xsl:param name="document" select="'false'" />

    <!-- The input's top node: a document, or (as the server passes it) a
         detached element. Default mode falls through to exactly the
         template that matched before (next-match). -->
    <xsl:template match="/ | *[not(parent::node())]" priority="10">
        <xsl:choose>
            <xsl:when test="$document = 'true'">
                <xsl:variable name="raw"><xsl:apply-templates select="." mode="doc" /></xsl:variable>
                <xsl:variable name="lines" select="replace(string($raw), '[ \t]*\n[ \t]*', '&#10;')" />
                <xsl:value-of select="replace(replace($lines, '\n{3,}', '&#10;&#10;'), '^\s+|\s+$', '')" />
            </xsl:when>
            <xsl:otherwise><xsl:next-match /></xsl:otherwise>
        </xsl:choose>
    </xsl:template>

    <xsl:template match="text()" mode="doc">
        <xsl:value-of select="replace(., '\s+', ' ')" />
    </xsl:template>

    <xsl:template match="tei:head | tei:div | tei:lg | tei:list | tei:table | tei:sp | tei:castList" mode="doc">
        <xsl:text>&#10;</xsl:text>
        <xsl:apply-templates mode="doc" />
        <xsl:text>&#10;&#10;</xsl:text>
    </xsl:template>

    <xsl:template match="tei:p | tei:l | tei:item | tei:row | tei:subtitle | tei:trailer | tei:epigraph
                         | tei:opener | tei:closer | tei:salute | tei:signed | tei:dateline | tei:byline
                         | tei:docTitle | tei:titlePart | tei:docAuthor | tei:docImprint | tei:argument
                         | tei:speaker | tei:stage | tei:castItem | tei:bibl | tei:address | tei:addrLine"
                  mode="doc">
        <xsl:apply-templates mode="doc" />
        <xsl:text>&#10;</xsl:text>
    </xsl:template>

    <xsl:template match="tei:cell" mode="doc">
        <xsl:apply-templates mode="doc" />
        <xsl:text> </xsl:text>
    </xsl:template>

    <xsl:template match="tei:lb" mode="doc">
        <xsl:text>&#10;</xsl:text>
    </xsl:template>

    <xsl:template match="tei:figure | tei:binaryObject" mode="doc">
        <xsl:text>&#10;[IMAGE].&#10;</xsl:text>
    </xsl:template>

    <xsl:template match="tei:label" mode="doc">
        <xsl:apply-templates mode="doc" />
        <xsl:text> </xsl:text>
    </xsl:template>

    <xsl:template match="tei:note" mode="doc">
        <xsl:text> [</xsl:text>
        <xsl:value-of select="normalize-space(.)" />
        <xsl:text>]</xsl:text>
    </xsl:template>

    <!-- default mode: one paragraph-level element; unchanged -->

    <xsl:template match="tei:head">
        <xsl:apply-templates />
        <xsl:if test="position() != last()"><xsl:text>&#10;&#10;</xsl:text></xsl:if>
    </xsl:template>

    <xsl:template match="tei:p | tei:lg | tei:table">
        <xsl:apply-templates />
        <xsl:if test="position() != last()"><xsl:text>&#10;</xsl:text></xsl:if>
    </xsl:template>

    <xsl:template match="tei:l">
        <xsl:apply-templates />
        <xsl:if test="position() != last()"><xsl:text>&#10;</xsl:text></xsl:if>
    </xsl:template>

    <xsl:template match="tei:lb">
        <xsl:text>&#10;</xsl:text>
    </xsl:template>

    <xsl:template match="tei:figure | tei:binaryObject">
        <xsl:text>
            [IMAGE].
            &#10;
        </xsl:text>
    </xsl:template>

    <xsl:template match="tei:label">
        <xsl:apply-templates />
        <xsl:text> </xsl:text>
    </xsl:template>

    <xsl:template match="tei:note">
        <xsl:text> [</xsl:text>
            <xsl:apply-templates />
        <xsl:text>]&#10;</xsl:text>
    </xsl:template>

</xsl:stylesheet>


