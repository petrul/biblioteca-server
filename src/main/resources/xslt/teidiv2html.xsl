<?xml version="1.0" encoding="utf-8"?>
<xsl:stylesheet
        xmlns:tei="http://www.tei-c.org/ns/1.0"
        xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
        exclude-result-prefixes="tei"
        version="2.0">

    <xsl:param name="requestURI" select="default" />
    <xsl:param name="author"  select="default" />
    <xsl:param name="relativeRoot"  select="default" />

    <xsl:output indent="yes" omit-xml-declaration="yes"/>

    <xsl:template match="/tei:TEI">
        <xsl:apply-templates select="/tei:TEI/tei:text/tei:body/tei:div"/>
    </xsl:template>

    <xsl:template match="@*|node()">
        <xsl:copy>
            <xsl:apply-templates select="@*|node()"/>
        </xsl:copy>
    </xsl:template>

    <!-- special rule for root div -->
    <xsl:template match="tei:div">
        <xsl:element name="div">
            <xsl:if test="@rend">
                <xsl:attribute name="class" select="@rend" />
            </xsl:if>
            <xsl:apply-templates select="node()|@*"/>
        </xsl:element>
    </xsl:template>


    <xsl:template match="tei:*">
        <xsl:element name="{local-name()}">
            <xsl:if test="@rend">
                <xsl:attribute name="class" select="@rend" />
            </xsl:if>
            <xsl:copy-of select="namespace::*[not(. = namespace-uri(..))]"/>
            <xsl:apply-templates select="node()|@*"/>
        </xsl:element>
    </xsl:template>

    <xsl:template match="tei:head//tei:label">
        <span class="head-label"><xsl:apply-templates /></span>
    </xsl:template>

    <xsl:template match="tei:head">
        <xsl:choose>
            <xsl:when test="../@type">
                <xsl:if test="../@type='div1'">
                    <h1><xsl:apply-templates/></h1>
                    <p class="deco-title-author-separator">
                        ❖
<!--                        <img class="deco-title-author-separator">-->
<!--                        <xsl:attribute name="src">-->
<!--                            <xsl:text>..</xsl:text><xsl:value-of select="$relativeRoot"/><xsl:text>/img/deco/deco-hr-style-2.svg</xsl:text>-->
<!--                        </xsl:attribute>-->
<!--                    </img>-->
                    </p>
                    <h2 class="author"><xsl:value-of select="$author"/></h2>
                </xsl:if>
                <xsl:if test="../@type='div2'">
                    <h2><xsl:apply-templates/></h2>
                </xsl:if>
                <xsl:if test="../@type='div3'">
                    <h3><xsl:apply-templates/></h3>
                </xsl:if>
                <xsl:if test="../@type='div4'">
                    <h4><xsl:apply-templates/></h4>
                </xsl:if>
                <xsl:if test="../@type='div5'">
                    <h5><xsl:apply-templates/></h5>
                </xsl:if>
                <xsl:if test="../@type='div6'">
                    <h6><xsl:apply-templates/></h6>
                </xsl:if>
                <xsl:if test="../@type='div7'">
                    <h7><xsl:apply-templates/></h7>
                </xsl:if>
                <xsl:if test="../@type='div8'">
                    <h8><xsl:apply-templates/></h8>
                </xsl:if>
            </xsl:when>

            <xsl:otherwise>
                <p class="unspecified-heading">
                    <xsl:apply-templates/>
                </p>
            </xsl:otherwise>
        </xsl:choose>
    </xsl:template>


    <xsl:template match="tei:head//tei:lb">
        <xsl:text>&#x20;</xsl:text>
    </xsl:template>

    <!-- in jules verne, odt from word doc contains some refs in the heading, just ignore -->
    <xsl:template match="tei:head/tei:anchor"></xsl:template>
    <xsl:template match="tei:head/tei:ptr"></xsl:template>


    <xsl:template match="tei:hi">
        <xsl:choose>
            <xsl:when test="@rend = 'italic'"><em><xsl:apply-templates /></em></xsl:when>
            <xsl:otherwise><em><xsl:apply-templates /></em></xsl:otherwise>
        </xsl:choose>
    </xsl:template>
    <xsl:template match="tei:q">
        <q><xsl:apply-templates /></q>
    </xsl:template>

    <xsl:template match="tei:note">
        <xsl:variable name="var">
            <xsl:apply-templates />
        </xsl:variable>

        <button type="button" class="btn note" data-toggle="tooltip" data-placement="top">
            <xsl:attribute name="title"><xsl:value-of select="$var"></xsl:value-of> </xsl:attribute>
            <sup><xsl:value-of select="@n" /></sup>
        </button>

    </xsl:template>

    <xsl:template match="tei:lb"><br/></xsl:template>

    <xsl:template match="tei:lg">
        <div class="lg"><xsl:apply-templates /></div>
    </xsl:template>

    <xsl:template match="tei:l">
        <p class="l"><xsl:apply-templates /></p>
    </xsl:template>

    <!-- Drama: a speech (sp), its printed speaker line, and stage
         directions - produced by the scriptorium-masters post hook
         over the hardcoded drama work list. The who pointer rides
         along as data-who (character-level features in the reader). -->
    <xsl:template match="tei:sp">
        <div class="sp">
            <xsl:if test="@who">
                <xsl:attribute name="data-who">
                    <xsl:value-of select="translate(@who, '#', '')" />
                </xsl:attribute>
            </xsl:if>
            <xsl:apply-templates />
        </div>
    </xsl:template>

    <xsl:template match="tei:speaker">
        <span class="speaker"><xsl:apply-templates /></span>
    </xsl:template>

    <xsl:template match="tei:stage">
        <p class="stage"><xsl:apply-templates /></p>
    </xsl:template>

    <xsl:template match="tei:epigraph">
        <p class="epigraph"><xsl:apply-templates /></p>
    </xsl:template>
    <xsl:template match="tei:subtitle">
        <p class="subtitle"><xsl:apply-templates /></p>
    </xsl:template>
    <xsl:template match="tei:trailer">
        <p class="trailer"><xsl:apply-templates /></p>
    </xsl:template>
    <xsl:template match="tei:opener">
        <p class="opener"><xsl:apply-templates /></p>
    </xsl:template>
    <xsl:template match="tei:closer">
        <p class="closer"><xsl:apply-templates /></p>
    </xsl:template>
    <xsl:template match="tei:quote">
        <blockquote><xsl:apply-templates /></blockquote>
    </xsl:template>

    <xsl:template match="tei:ptr">
        <a>
            <xsl:attribute name="href"><xsl:value-of select="@target" /></xsl:attribute>
            <xsl:choose>
                <xsl:when test="./*"><xsl:apply-templates /></xsl:when>
                <xsl:otherwise><xsl:value-of select="@target" /></xsl:otherwise>
            </xsl:choose>
        </a>
    </xsl:template>
    <xsl:template match="tei:sourceDesc"><xsl:apply-templates /></xsl:template>

    <xsl:template match="tei:table">
        <table><xsl:apply-templates /></table>
    </xsl:template>
    <xsl:template match="tei:row">
        <tr><xsl:apply-templates /></tr>
    </xsl:template>
    <xsl:template match="tei:cell">
        <td><xsl:apply-templates /></td>
    </xsl:template>
    <xsl:template match="tei:figure">
        <img class="illustration">
            <xsl:attribute name="src"><xsl:value-of select="$requestURI"/>/_binary/<xsl:value-of select="tei:binaryObject/@xml:id"/></xsl:attribute>
        </img>
    </xsl:template>

</xsl:stylesheet>