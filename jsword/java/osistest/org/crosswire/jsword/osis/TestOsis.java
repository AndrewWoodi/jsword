
package org.crosswire.jsword.osis;

import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.bind.ValidationEvent;
import javax.xml.bind.ValidationEventHandler;
import javax.xml.bind.Validator;

import org.crosswire.jsword.book.data.OsisUtil;

import junit.framework.TestCase;

/**
 * JUnit Test.
 * 
 * <p><table border='1' cellPadding='3' cellSpacing='0'>
 * <tr><td bgColor='white' class='TableRowColor'><font size='-7'>
 *
 * Distribution Licence:<br />
 * JSword is free software; you can redistribute it
 * and/or modify it under the terms of the GNU General Public License,
 * version 2 as published by the Free Software Foundation.<br />
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.<br />
 * The License is available on the internet
 * <a href='http://www.gnu.org/copyleft/gpl.html'>here</a>, or by writing to:
 * Free Software Foundation, Inc., 59 Temple Place - Suite 330, Boston,
 * MA 02111-1307, USA<br />
 * The copyright to this program is held by it's authors.
 * </font></td></tr></table>
 * @see docs.Licence
 * @author Joe Walker [joe at eireneh dot com]
 * @version $Id$
 */
public class TestOsis extends TestCase
{
    public TestOsis(String s)
    {
        super(s);
    }

    protected void setUp() throws Exception
    {
        jc = JAXBContext.newInstance(OsisUtil.OSIS_PACKAGE);
        val = jc.createValidator();
        umarsh = jc.createUnmarshaller();
        m = jc.createMarshaller();
    }

    protected void tearDown() throws Exception
    {
    }

    private JAXBContext jc = null;
    private Validator val = null;
    private Unmarshaller umarsh = null;
    private Marshaller m = null;

    public void testManual() throws Exception
    {
        try
        {
            Seg seg = ObjectFactory.createSeg();
            seg.getContent().add("In the beginning God created the heaven and the earth.");

            Verse verse = ObjectFactory.createVerse();
            verse.setOsisID("Gen.1.1");
            verse.getContent().add(seg);
    
            Div div = ObjectFactory.createDiv();
            div.setType("chapter");
            div.setOsisID("Gen.1.1");
            div.getContent().add(verse);
    
            Work work = ObjectFactory.createWork();
    
            Header header = ObjectFactory.createHeader();
            header.getWork().add(work);
    
            OsisText osistext = ObjectFactory.createOsisText();
            osistext.setOsisIDWork("Bible.KJV");
            osistext.getDiv().add(div);
            osistext.setHeader(header);

            Osis blank = ObjectFactory.createOsis();
            blank.setOsisText(osistext);
    
            val.setEventHandler(new ValidationEventHandler()
            {
                public boolean handleEvent(ValidationEvent ev)
                {
                    return false;
                }
            });
            val.validateRoot(blank);
    
            // create a Marshaller and marshal to System.out
            m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
            m.marshal(blank, System.out);
        }
        catch (JAXBException ex)
        {
            ex.printStackTrace();
        }
    }

    public void testLoadSave() throws Exception
    {
        try
        {
            String zipfile = "T:\\share\\joe\\jsword\\notes\\kjv.zip";
            
            ZipFile zfile = new ZipFile(new File(zipfile));
            ZipEntry zentry = zfile.getEntry("kjv.xml");
            InputStream zin = zfile.getInputStream(zentry);
            
            Osis kjv = (Osis) umarsh.unmarshal(zin);
            val.setEventHandler(new ValidationEventHandler()
            {
                public boolean handleEvent(ValidationEvent ev)
                {
                    return false;
                }
            });
            val.validateRoot(kjv);
        }
        catch (JAXBException ex)
        {
            ex.printStackTrace();
        }
    }
}