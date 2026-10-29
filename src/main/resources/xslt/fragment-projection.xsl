<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <!-- Import the actual indexing projection; markers never enter stored vectors. -->
  <xsl:import href="tei2text.xsl"/>
  <xsl:param name="marker"/>
  <xsl:template match="*" priority="100">
    <xsl:value-of select="concat($marker, 'S', @data-projection-id, ';')"/>
    <xsl:next-match/>
    <xsl:value-of select="concat($marker, 'E', @data-projection-id, ';')"/>
  </xsl:template>
  <xsl:template match="text()" priority="100">
    <xsl:variable name="id" select="concat(../@data-projection-id, 't', count(preceding-sibling::text()) + 1)"/>
    <xsl:value-of select="concat($marker, 'S', $id, ';')"/>
    <xsl:value-of select="."/>
    <xsl:value-of select="concat($marker, 'E', $id, ';')"/>
  </xsl:template>
</xsl:stylesheet>
