package org.crosswire.common.swing;

import java.lang.reflect.Method;

import javax.swing.JOptionPane;
import javax.swing.LookAndFeel;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;

import org.crosswire.common.util.Logger;

/**
 * LookAndFeelUtil declares the Choices and actions
 * needed to dynamically change the look and feel (PLAF) and to add new
 * PLAFs without needing to restart.
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
 * @author Mark Goodwin [mark at thorubio dot org]
 * @author DM Smith [dmsmith555 at hotmail dot com]
 * @version $Id$
 */
public class LookAndFeelUtil
{
    /**
     * Prevent Instansiation
     */
    private LookAndFeelUtil()
    {
    }

    /**
     * The Options customization
     */
    public static Class getLookAndFeel()
    {
        return current;
    }

    /**
     * The Options customization
     */
    public static void setLookAndFeel(Class new_class) throws InstantiationException, IllegalAccessException, UnsupportedLookAndFeelException
    {
        LookAndFeel laf = (LookAndFeel) new_class.newInstance();

        // new_class is null if the user enters a bogus value
        if (current != null && !new_class.equals(current))
        {
            JOptionPane.showMessageDialog(null, Msg.PLAF_CHANGE);
        }
        else
        {
            UIManager.setLookAndFeel(laf);
        }

        current = new_class;
    }

    /**
     * Hack the windows look and feel to make the fonts more readable on
     * bigger screens.
     */
    public static void tweakLookAndFeel()
    {
        try
        {
            System.setProperty("winlaf.forceTahoma", "true"); //$NON-NLS-1$ //$NON-NLS-2$
            Class clazz = Class.forName("net.java.plaf.LookAndFeelPatchManager"); //$NON-NLS-1$
            Method init = clazz.getMethod("initialize", new Class[0]); //$NON-NLS-1$
            init.invoke(null, new Object[0]);

            log.debug("installed Windows LookAndFeelPatchManager"); //$NON-NLS-1$
        }
        catch (Exception ex)
        {
            log.warn("Failed to install windows laf tweak tool: "+ex); //$NON-NLS-1$
        }

        LookAndFeel currentlnf = UIManager.getLookAndFeel();
        if (currentlnf.getClass().getName().equals("com.sun.java.swing.plaf.windows.WindowsLookAndFeel")) //$NON-NLS-1$
        {
            /*
            UIDefaults defaults = UIManager.getDefaults();
            Font menufont = defaults.getFont("Menu.font");
    
            defaults.put("ProgressBar.font", menufont);
            defaults.put("ToggleButton.font", menufont);
            defaults.put("Panel.font", menufont);
            defaults.put("TableHeader.font", menufont);
            defaults.put("TextField.font", menufont);
            defaults.put("Button.font", menufont);
            defaults.put("Label.font", menufont);
            defaults.put("ScrollPane.font", menufont);
            defaults.put("List.font", menufont);
            defaults.put("EditorPane.font", menufont);
            defaults.put("Table.font", menufont);
            defaults.put("TabbedPane.font", menufont);
            defaults.put("RadioButton.font", menufont);
            defaults.put("TextPane.font", menufont);
            defaults.put("TitledBorder.font", menufont);
            defaults.put("ComboBox.font", menufont);
            defaults.put("CheckBox.font", menufont);
            defaults.put("Tree.font", menufont);
            defaults.put("Viewport.font", menufont);
            */
        }
    }

    /**
     * The current PLAF (and the default value)
     */
    private static Class current;

    /**
     * The log stream
     */
    private static final Logger log = Logger.getLogger(LookAndFeelUtil.class);

    /**
     * Setup the defaults Hashtable
     */
    static
    {
        // try to set the default look and feel to the system default
        try
        {
            String lafClassName = UIManager.getSystemLookAndFeelClassName();
            current = Class.forName(lafClassName);
            UIManager.setLookAndFeel(lafClassName);
        }
        catch (Exception ex)
        {
            log.warn("Failed to initialise system default LAF", ex); //$NON-NLS-1$
            current = javax.swing.plaf.metal.MetalLookAndFeel.class;
        }
 
        /*
        Class[] impls = Project.resource().getImplementors(LookAndFeel.class);
        for (int i=0; i<impls.length; i++)
        {
            LookAndFeel lnf = (LookAndFeel) impls[i].newInstance();
            defaults.put(lnf.getName(), impls[i].getName());
        }
        */
    }
}