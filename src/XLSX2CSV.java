import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException; 
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.Format;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.metamodel.util.DateUtils;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;



/**
 * A rudimentary XLSX -> CSV processor modeled on the
 * POI sample program XLS2CSVmra by Nick Burch from the
 * package org.apache.poi.hssf.eventusermodel.examples.
 * Unlike the HSSF version, this one completely ignores 
 * missing rows.
 * 
 * Data sheets are read using a SAX parser to keep the 
 * memory footprint relatively small, so this should be
 * able to read enormous workbooks.  The styles table and
 * the shared-string table must be kept in memory.  The
 * standard POI styles table class is used, but a custom
 * (read-only) class is used for the shared string table
 * because the standard POI SharedStringsTable grows very
 * quickly with the number of unique strings.
 * 
 * @author Chris Lott
 */
public class XLSX2CSV {

	/**
	 * The type of the data value is indicated by an attribute on 
	 * the cell element; the value is in a "v" element within the cell.
	 */
	enum xssfDataType {
		BOOL,
		ERROR,
		FORMULA,
		INLINESTR,
		SSTINDEX,
		NUMBER,
	}

	/**
	 * Derived from http://poi.apache.org/spreadsheet/how-to.html#xssf_sax_api
	 * 
	 * Also see Standard ECMA-376, 1st edition, part 4, pages 1928ff, at
	 * http://www.ecma-international.org/publications/standards/Ecma-376.htm
	 * 
	 * A web-friendly version is http://openiso.org/Ecma/376/Part4
	 */
	class MyXSSFSheetHandler extends DefaultHandler {

		public DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
		/** Table with styles */
		private StylesTable stylesTable;

		/** Table with unique strings */
		private ReadOnlySharedStringsTable sharedStringsTable;

		/** Destination for data */
		private final PrintStream output;

		/** Number of columns to read starting with leftmost */
		private final int minColumnCount;

		// Set when V start element is seen
		private boolean vIsOpen;

		// Set when cell start element is seen;
		// used when cell close element is seen.
		private xssfDataType nextDataType;

		// Used to format numeric cell values.
		private short formatIndex;
		private String formatString;
		private final DataFormatter formatter;

		private int thisColumn = -1;
		// The last column printed to the output stream
		private int lastColumnNumber = -1;

		// Gathers characters as they are seen.
		private StringBuffer value;

		/**
		 * Accepts objects needed while parsing.
		 * 
		 * @param styles Table of styles
		 * @param strings Table of shared strings
		 * @param cols Minimum number of columns to show
		 * @param target Sink for output
		 */
		public MyXSSFSheetHandler(
				StylesTable styles,
				ReadOnlySharedStringsTable strings,
				int cols,
				PrintStream target) {
			this.stylesTable = styles;
			this.sharedStringsTable = strings;
			this.minColumnCount = cols;
			this.output = target;
			this.value = new StringBuffer();
			this.nextDataType = xssfDataType.NUMBER;
			this.formatter = new DataFormatter();
		}

		/*
		 * (non-Javadoc)
		 * @see org.xml.sax.helpers.DefaultHandler#startElement(java.lang.String, java.lang.String,
java.lang.String, org.xml.sax.Attributes)
		 */
		public void startElement(String uri, String localName, String name,
				Attributes attributes) throws SAXException {

			if ("inlineStr".equals(name) || "v".equals(name)) {
				vIsOpen = true; 
				// Clear contents cache
				value.setLength(0);
			}
			// c => cell
			else if ("c".equals(name)) {
				// Get the cell reference
				String r = attributes.getValue("r");
				int firstDigit = -1;
				for (int c = 0; c < r.length(); ++c) {
					if (Character.isDigit(r.charAt(c))) {
						firstDigit = c;
						break;
					}
				}
				thisColumn = nameToColumn(r.substring(0, firstDigit));

				// Set up defaults.
				this.nextDataType = xssfDataType.NUMBER;
				this.formatIndex = -1;
				this.formatString = null;
				String cellType = attributes.getValue("t");
				String cellStyleStr = attributes.getValue("s");
				if ("b".equals(cellType))
					nextDataType = xssfDataType.BOOL;
				else if ("e".equals(cellType))
					nextDataType = xssfDataType.ERROR;
				else if ("inlineStr".equals(cellType))
					nextDataType = xssfDataType.INLINESTR;
				else if ("s".equals(cellType))
					nextDataType = xssfDataType.SSTINDEX;
				else if ("str".equals(cellType))
					nextDataType = xssfDataType.FORMULA;
				else if (cellStyleStr != null) {
					/*
					 * It's a number, but possibly has a style and/or special format.
					 * Nick Burch said to use org.apache.poi.ss.usermodel.BuiltinFormats, 
					 * and I see javadoc for that at apache.org, but it's not in the
					 * POI 3.5 Beta 5 jars.  Scheduled to appear in 3.5 beta 6.
					 */
					int styleIndex = Integer.parseInt(cellStyleStr);
					XSSFCellStyle style = stylesTable.getStyleAt(styleIndex);
					this.formatIndex = style.getDataFormat();
					this.formatString = style.getDataFormatString();
					if (this.formatString == null)
						this.formatString = BuiltinFormats.getBuiltinFormat(this.formatIndex);
				}
			}

		}

		/*
		 * (non-Javadoc)
		 * @see org.xml.sax.helpers.DefaultHandler#endElement(java.lang.String, java.lang.String,
java.lang.String)
		 */
		public void endElement(String uri, String localName, String name)
		throws SAXException {

			String thisStr = null;

			// v => contents of a cell
			if ("v".equals(name)) {
				// Process the value contents as required.
				// Do now, as characters() may be called more than once
				switch(nextDataType) {

				case BOOL:
					char first = value.charAt(0);
					thisStr = first == '0' ? "FALSE" : "TRUE";
					break;

				case ERROR:
					thisStr = "\"ERROR:" + value.toString() + '"';
					break;	

				case FORMULA: 
					// A formula could result in a string value,
					// so always add double-quote characters.
					thisStr = '"' + value.toString() + '"';
					break;

				case INLINESTR:
					// TODO: have seen an example of this, so it's untested.
					XSSFRichTextString rtsi = new XSSFRichTextString(value.toString());
					thisStr = '"' + rtsi.toString() + '"'; 
					break;

				case SSTINDEX:
					String sstIndex = value.toString();
					try {
						int idx = Integer.parseInt(sstIndex);
						XSSFRichTextString rtss = new XSSFRichTextString(sharedStringsTable.getEntryAt(idx));
						thisStr = '"' + rtss.toString() + '"'; 
					}
					catch (NumberFormatException ex) {
						output.println("Failed to parse SST index '" + sstIndex + "': " + ex.toString());
					}
					break;

				case NUMBER:
					String n = value.toString();

					if (this.formatString != null)
						if(DateUtil.isADateFormat(this.formatIndex,this.formatString)){
							Date date = DateUtil.getJavaDate(Double.parseDouble(n));
							String df = DateUtils.createDateFormat().format(date);
							thisStr = df.substring(0,10);
						} else
							thisStr = formatter.formatRawCellContents(Double.parseDouble(n), this.formatIndex, this.formatString);
					else
						thisStr = n;
					break;

				default:
					thisStr = "(TODO: Unexpected type: " + nextDataType + ")";
				break;
				}

				// Output after we've seen the string contents
				// Emit commas for any fields that were missing on this row
				if(lastColumnNumber == -1) { lastColumnNumber = 0; }
				for (int i = lastColumnNumber; i < thisColumn; ++i)
					output.print(',');

				// Might be the empty string.
				output.print(thisStr);

				// Update column 
				if(thisColumn > -1)
					lastColumnNumber = thisColumn;

			}
			else if("row".equals(name)) {

				// Print out any missing commas if needed
				if(minColumns > 0) {
					// Columns are 0 based
					if(lastColumnNumber == -1) { lastColumnNumber = 0; }
					for(int i=lastColumnNumber; i<(this.minColumnCount); i++) {
						output.print(',');
					}
				}

				// We're onto a new row
				output.print('\n');
				lastColumnNumber = -1;
			}

		}

		/**
		 * Captures characters only if a suitable element is open.
		 * Originally was just "v"; extended for inlineStr also.
		 */
		public void characters(char[] ch, int start, int length)
		throws SAXException {
			if (vIsOpen)
				value.append(ch, start, length);
		}

		/**
		 * Converts an Excel column name like "C" to a zero-based index.
		 * @param name
		 * @return Index corresponding to the specified name
		 */
		private int nameToColumn(String name) {
			int column = -1;
			for (int i = 0; i < name.length(); ++i) {
				int c = name.charAt(i);
				column = (column + 1) * 26 + c - 'A';
			}
			return column;
		}

	}

	///////////////////////////////////////

	private OPCPackage xlsxPackage;
	private int minColumns;
	private PrintStream output;

	/**
	 * Creates a new XLSX -> CSV converter
	 * Javadoc says I should use OPCPackage instead of Package, but OPCPackage 
	 * was not available in the POI 3.5-beta5 build I used.
	 * 
	 * @param pkg The XLSX package to process
	 * @param output The PrintStream to output the CSV to
	 * @param minColumns The minimum number of columns to output, or -1 for no minimum
	 */
	public XLSX2CSV(OPCPackage pkg, PrintStream output, int minColumns) {
		this.xlsxPackage = pkg;
		this.output = output;
		this.minColumns = minColumns;
	}

	public XLSX2CSV() {
		// TODO Auto-generated constructor stub
	}

	/**
	 * Parses and shows the content of one sheet
	 * using the specified styles and shared-strings tables.
	 * @param styles
	 * @param strings
	 * @param sheetInputStream
	 */
	public void processSheet(
			StylesTable styles,
			ReadOnlySharedStringsTable strings, 
			InputStream sheetInputStream) 
	throws IOException, ParserConfigurationException, SAXException {

		InputSource sheetSource = new InputSource(sheetInputStream);
		SAXParserFactory saxFactory = SAXParserFactory.newInstance();
		SAXParser saxParser = saxFactory.newSAXParser();
		XMLReader sheetParser = saxParser.getXMLReader();
		ContentHandler handler = new MyXSSFSheetHandler(styles, strings, this.minColumns, this.output);
		sheetParser.setContentHandler(handler);
		sheetParser.parse(sheetSource);
	}

	/**
	 * Initiates the processing of the XLS workbook file to CSV.
	 * @throws IOException
	 * @throws OpenXML4JException
	 * @throws ParserConfigurationException
	 * @throws SAXException 
	 */
	public void process() 
	throws IOException, OpenXML4JException, ParserConfigurationException, SAXException {

		ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(this.xlsxPackage);
		XSSFReader xssfReader = new XSSFReader(this.xlsxPackage);
		StylesTable styles = xssfReader.getStylesTable(); 
		XSSFReader.SheetIterator iter = (XSSFReader.SheetIterator)xssfReader.getSheetsData();
		int index = 0;
		while (index == 0) {
			InputStream stream = iter.next();
			String sheetName = iter.getSheetName();
			processSheet(styles, strings, stream);
			stream.close();
			++index;
		}		
	}

    public String xlsx2csvConverter(File xlsxFile)  throws Exception {

		// If no log4j configuration is provided, these messages appear:
		//   log4j:WARN No appenders could be found for logger (org.openxml4j.opc).
		//   log4j:WARN Please initialize the log4j system properly.
		// If only the BasicConfigurator.configure() is done, these messages appear:
		//   0 [main] DEBUG org.openxml4j.opc  - Parsing relationship: /xl/_rels/workbook.xml.rels
		//  46 [main] DEBUG org.openxml4j.opc  - Parsing relationship: /_rels/.rels
		// Added the call to setLevel() to turn these off, now I see nothing.

		BasicConfigurator.configure();
		Logger.getRootLogger().setLevel(Level.INFO);

        int minColumns = -1;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
		// The package open is instantaneous, as it should be.
		OPCPackage p = OPCPackage.open(xlsxFile.getPath(), PackageAccess.READ);
		XLSX2CSV xlsx2csv = new XLSX2CSV(p, ps, minColumns);
		xlsx2csv.process();
		// Want to call close() here, but the package is open for read,
		// so it's not necessary, and it complains if I do call it!
		p.close();
		String content = new String(baos.toByteArray(), StandardCharsets.UTF_8);
		System.out.println("Yaayy Sriniz!!! Conversion is done..");
        return content;
	}

}