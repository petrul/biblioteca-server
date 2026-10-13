-- Earlier imports built descriptions from the opening paragraphs' raw text,
-- which for works opening with an embedded figure was its base64 image data.
-- Cleared here; re-importing the work regenerates a proper description.
UPDATE TEI_OPUS SET DESCRIPTION = NULL
WHERE DESCRIPTION LIKE '/9j/%'
   OR DESCRIPTION LIKE 'iVBORw0KGgo%'
   OR DESCRIPTION LIKE 'R0lGOD%';
