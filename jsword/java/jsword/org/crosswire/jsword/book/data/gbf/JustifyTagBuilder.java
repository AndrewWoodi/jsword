package org.crosswire.jsword.book.data.gbf;

import java.util.LinkedList;

import javax.xml.bind.Element;
import javax.xml.bind.JAXBException;

import org.crosswire.jsword.book.data.JAXBUtil;
import org.crosswire.jsword.osis.Seg;

/**
 * Handle Footnotes: FR and Fr.
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
 * @see gnu.gpl.Licence
 * @author Joe Walker [joe at eireneh dot com]
 * @version $Id$
 */
public class JustifyTagBuilder implements TagBuilder
{
    /* (non-Javadoc)
     * @see org.crosswire.jsword.book.data.gbf.TagBuilder#createTag(java.lang.String)
     */
    public Tag createTag(String name)
    {
        if ("JR".equals(name))
        {
            return new Tag()
            {
                public void updateOsisStack(LinkedList stack) throws JAXBException
                {
                    // PENDING(joe): is div the right thing?
                    Seg seg = JAXBUtil.factory().createSeg();
                    seg.setType(JAXBUtil.SEG_JUSTIFYRIGHT);

                    Element current = (Element) stack.get(0);
                    JAXBUtil.getList(current).add(seg);
                    stack.addFirst(seg);
                }
            };
        }
    
        if ("JL".equals(name))
        {
            return new Tag()
            {
                public void updateOsisStack(LinkedList stack) throws JAXBException
                {
                    stack.removeFirst();
                }
            };
        }
    
        return null;
    }        
}
