
package org.crosswire.jsword.book.sword;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import org.crosswire.common.util.Logger;
import org.crosswire.common.util.NetUtil;
import org.crosswire.jsword.book.BookException;
import org.crosswire.jsword.passage.Verse;

/**
 * A backend to read GZIPped data files.
 * 
 * <p>There are 3 files, 2 (comp and idx) are indexes into the third (text)
 * which contains the data. I'm not sure why we need 2 indexes, but that's the
 * way it is done any it is too late to change it now.
 * <p>In addition there is a separate set of files for each testament. So for
 * each read you will need to know the testament from which to read and an index
 * (derived from the book, chapter and verse) within that testament.
 * <p>All numbers are stored 2-complement, little endian.
 * <p>Then proceed as follows, at all times working on the set of files for the
 * testament in question:
 * 
 * <pre>
 * in the comp file, seek to the index * 10
 * read 10 bytes.
 * the compressed-buffer-index is the first 4 bytes (32-bit number)
 * the remaining bytes are ignored
 * 
 * in the idx file seek to compressed-buffer-index * 12
 * read 12 bytes
 * the text-buffer-index is the first 4 bytes
 * the compressed-size is the next 4 bytes
 * the uncompressed-size is the next 4 bytes
 * 
 * in the text file seek to the text-buffer-index
 * read compressed-size bytes
 * //decipher them. wont this change their size? 
 * unGZIP them and check for uncompressed-size
 * </pre>
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
public class GZIPBackend implements Backend
{
    /* (non-Javadoc)
     * @see org.crosswire.jsword.book.sword.Backend#init(org.crosswire.jsword.book.sword.SwordConfig)
     */
    public void init(SwordConfig config) throws BookException
    {
        URL swordBase = SwordBookDriver.dir;

        try
        {
            URL url = NetUtil.lengthenURL(swordBase, config.getDataPath());
            if (!url.getProtocol().equals("file"))
            {
                throw new BookException(Msg.FILE_ONLY, new Object[] { url.getProtocol()});
            }

            String path = url.getFile();

            int blockType = config.getBlockType();

            try
            {
                String allbutlast = path + File.separator + "ot." + UNIQUE_INDEX_ID[blockType] + "z";

                idx_raf[SwordConstants.TESTAMENT_OLD] = new RandomAccessFile(allbutlast + "s", "r");
                text_raf[SwordConstants.TESTAMENT_OLD] = new RandomAccessFile(allbutlast + "z", "r");
                comp_raf[SwordConstants.TESTAMENT_OLD] = new RandomAccessFile(allbutlast + "v", "r");
            }
            catch (FileNotFoundException ex)
            {
                // Ignore this might be NT only
                log.debug("Missing index: "+path + File.separator + "ot." + UNIQUE_INDEX_ID[blockType] + "zs");
            }

            try
            {
                String allbutlast = path + File.separator + "nt." + UNIQUE_INDEX_ID[blockType] + "z";

                idx_raf[SwordConstants.TESTAMENT_NEW] = new RandomAccessFile(allbutlast + "s", "r");
                text_raf[SwordConstants.TESTAMENT_NEW] = new RandomAccessFile(allbutlast + "z", "r");
                comp_raf[SwordConstants.TESTAMENT_NEW] = new RandomAccessFile(allbutlast + "v", "r");
            }
            catch (FileNotFoundException ex)
            {
                // Ignore this might be OT only
                log.debug("Missing index: "+path + File.separator + "nt." + UNIQUE_INDEX_ID[blockType] + "zs");
            }

            // It is an error to be neither OT nor NT
            if (text_raf[SwordConstants.TESTAMENT_OLD] == null && text_raf[SwordConstants.TESTAMENT_NEW] == null)
            {
                throw new BookException(Msg.MISSING_FILE, new Object[] { url.getFile()});
            }

            // The original had a dtor that did the equiv of .close()ing the above
            // I'm not sure that there is a delete type ability in Book.java and
            // the finalizer for RandomAccessFile will do it anyway so for the
            // moment I'm going to ignore this.

            // The original also stored the path, but I don't think it ever used it

            // The original also kept an instance count, which went unused (and I
            // noticed in a few other places so it is either c&p or a pattern?
            // Either way the assumption that there is only one of a static is not
            // safe in many java environments (servlets, ejbs at least) so I've
            // deleted it
        }
        catch (MalformedURLException ex)
        {
            throw new BookException(Msg.NOT_FOUND, ex);
        }
    }

    /* (non-Javadoc)
     * @see org.crosswire.jsword.book.sword.Backend#getRawText(org.crosswire.jsword.passage.Verse)
     */
    public byte[] getRawText(Verse verse) throws BookException
    {
        try
        {
            VerseIndex vi = new VerseIndex(verse);

            // 10 because we the index is 10 bytes long for each verse
            byte[] temp = SwordUtil.readRAF(comp_raf[vi.getTestament()], vi.getIndex() * COMP_ENTRY_SIZE, COMP_ENTRY_SIZE);

            // The data is little endian - extract the start, size and endsize
            int buffernum = SwordUtil.decodeLittleEndian32AsInt(temp, 0);

            // These 2 bits of data are never read.
            int bstart = SwordUtil.decodeLittleEndian32AsInt(temp, 4);
            int bsize = SwordUtil.decodeLittleEndian16(temp, 8);
            log.debug("for "+verse.getName()+" bstart="+bstart+", bsize="+bsize);

            // Can we get the data from the cache
            byte[] uncompr = null;
            if (buffernum == lastbuffernum)
            {
                uncompr = lastuncompr;
                log.debug("cache hit on buffernum="+buffernum);
            }
            else
            {
                // Then seek using this index into the idx file
                temp = SwordUtil.readRAF(idx_raf[vi.getTestament()], buffernum * IDX_ENTRY_SIZE, IDX_ENTRY_SIZE);

                long start = SwordUtil.decodeLittleEndian32(temp, 0);
                int size = SwordUtil.decodeLittleEndian32AsInt(temp, 4);
                int endsize = SwordUtil.decodeLittleEndian32AsInt(temp, 8);

                // Read from the data file.
                byte[] compressed = SwordUtil.readRAF(text_raf[vi.getTestament()], start, size);

                // PENDING(joe): implement encryption?
                // byte[] decrypted = decrypt(compressed);

                uncompr = uncompress(compressed, endsize);

                // cache the uncompressed data for next time
                lastbuffernum = buffernum;
                lastuncompr = uncompr;
            }

            // and cut out the required section.
            byte[] chopped = new byte[bsize];
            System.arraycopy(uncompr, bstart, chopped, 0, bsize);

            return chopped;

            /* The code converted from Sword looked like this, but we can do better
            // buffer number
            comp_raf[testament].seek(offset);
            long buffernum = comp_raf[testament - 1].readInt();
            buffernum = swordtoarch32(buffernum);

            // verse offset within buffer
            // long versestart =
                comp_raf[testament - 1].readInt();
            // versestart = swordtoarch32(versestart);
            // short versesize =
                comp_raf[testament - 1].readShort();
            //versesize = swordtoarch16(versesize);

            idx_raf[testament].seek(buffernum * 12);

            // compressed buffer start
            long start = idx_raf[testament - 1].readInt();
            start = swordtoarch32(start);
            
            // buffer size compressed (was long but can't use long as array index)
            int size = idx_raf[testament - 1].readInt();
            size = swordtoarch32(size);
            
            // buffer size uncompressed (was long but can't use long as array index)
            int endsize = idx_raf[testament - 1].readInt();
            endsize = swordtoarch32(endsize);
            /**/
        }
        catch (Exception ex)
        {
            throw new BookException(Msg.READ_FAIL, ex);
        }
    }

    /**
     * Uncompress a block of GZIP compressed data
     * @param compressed
     * @param endsize
     * @return byte[]
     */
    public static byte[] uncompress(byte[] compressed, int endsize) throws IOException, DataFormatException, BookException
    {
        // Create the decompressor and give it the data to compress
        Inflater decompressor = new Inflater();
        decompressor.setInput(compressed);
    
        // Decompress the data
        byte[] uncompressed = new byte[endsize];
        int realendsize = decompressor.inflate(uncompressed);
        
        if (!decompressor.finished() || realendsize != endsize)
        {
            throw new BookException(Msg.GZIP_FORMAT, new Object[] { "wrong uncompressed size", } );
        }

        return uncompressed;
    }

    private int lastbuffernum = -1;
    private byte[] lastuncompr = null;

    /**
     * The log stream
     */
    private static Logger log = Logger.getLogger(GZIPBackend.class);

    /**
     * The array of index files
     */
    private RandomAccessFile[] idx_raf = new RandomAccessFile[3];

    /**
     * The array of data files
     */
    private RandomAccessFile[] text_raf = new RandomAccessFile[3];

    /**
     * The array of compressed files?
     */
    private RandomAccessFile[] comp_raf = new RandomAccessFile[3];

    /**
     * Yuck - they blocking method defines the initial file character.
     * The Sword original used: { 'X', 'r', 'v', 'c', 'b' }, however the values
     * we read from SwordConfig never matched up. Perhaps we need to work out
     * why.
     */
    private static final char[] UNIQUE_INDEX_ID = { 'b', 'c', 'v', };

    /**
     * How many bytes in the comp index?
     */
    private static final int COMP_ENTRY_SIZE = 10;

    /**
     * How many bytes in the idx index?
     */
    private static final int IDX_ENTRY_SIZE = 12;
}

// CODE THAT I'm FAIRLY SURE WE DONT NEED
/**
 * RawVerse::linkentry  - links one entry to another
 *
 * ENT: testmt  - testament to find (0 - Bible/module introduction)
 *  destidxoff  - dest offset into .vss
 *  srcidxoff       - source offset into .vss
 *
private void doLinkEntry(char testmt, long destidxoff, long srcidxoff)
{
    long bufidx;
    long start;
    short size;

    destidxoff *= 10;
    srcidxoff *= 10;

    if (!testmt)
        testmt = ((idxfp[1]) ? 1 : 2);

    // get source
    lseek(compfp[testmt - 1] - > getFd(), srcidxoff, SEEK_SET);
    read(compfp[testmt - 1] - > getFd(), & bufidx, 4);
    read(compfp[testmt - 1] - > getFd(), & start, 4);
    read(compfp[testmt - 1] - > getFd(), & size, 2);

    // write dest
    lseek(compfp[testmt - 1] - > getFd(), destidxoff, SEEK_SET);
    write(compfp[testmt - 1] - > getFd(), & bufidx, 4);
    write(compfp[testmt - 1] - > getFd(), & start, 4);
    write(compfp[testmt - 1] - > getFd(), & size, 2);
}


/*
 * zVerse::preptext - Prepares the text before returning it to external
 *              objects
 *
 * ENT: buf - buffer where text is stored and where to store the prep'd
 *          text.
 *
private void prepText(SWBuf buf)
{
    int to;
    int from;

    char space = 0;
    char cr = 0;
    char realdata = 0;
    char nlcnt = 0;

    for (to = from = 0; buf[from]; from++)
    {
        switch (buf[from])
        {
            case 10 :
                if (!realdata)
                    continue;
                space = (cr) ? 0 : 1;
                cr = 0;
                nlcnt++;
                if (nlcnt > 1)
                {
                    //              *to++ = nl;
                    buf[to++] = 10;
                    //              *to++ = nl[1];
                    //              nlcnt = 0;
                }
                continue;
            case 13 :
                if (!realdata)
                    continue;
                //          *to++ = nl[0];
                buf[to++] = 10;
                space = 0;
                cr = 1;
                continue;
        }
        realdata = 1;
        nlcnt = 0;

        if (space != 0)
        {
            space = 0;
            if (buf[from] != ' ')
            {
                buf[to++] = ' ';
                from--;
                continue;
            }
        }
        buf[to++] = buf[from];
    }
    buf.setSize(to);

    while (to > 1)
    { // remove trailing excess
        to--;
        if ((buf[to] == 10) || (buf[to] == ' '))
        {
            buf.setSize(to);
        }
        else
        {
            break;
        }
    }
}

/**
 * zVerse::settext  - Sets text for current offset
 *
 * ENT: testmt  - testament to find (0 - Bible/module introduction)
 *  idxoff  - offset into .vss
 *  buf - buffer to store
 *      len     - length of buffer (0 - null terminated)
 *
private void doSetText(char testmt, long idxoff, String buf, long len)
{

    len = (len < 0) ? strlen(buf) : len;
    if (!testmt)
        testmt = ((idxfp[0]) ? 1 : 2);

    if ((!dirtyCache) || (cacheBufIdx < 0))
    {
        cacheBufIdx = lseek(idxfp[testmt - 1].getFD(), 0, SEEK_END) / 12;
        cacheTestament = testmt;
        if (cacheBuf)
            free(cacheBuf);
        cacheBuf = (String) calloc(len + 1, 1);
    }
    else
    {
        cacheBuf = (String) ((cacheBuf) ? realloc(cacheBuf, strlen(cacheBuf) + (len + 1)) : calloc((len + 1), 1));
    }

    dirtyCache = true;

    long start, outstart;
    long outBufIdx = cacheBufIdx;
    short size;
    short outsize;

    idxoff *= 10;
    size = outsize = len;

    start = strlen(cacheBuf);

    if (!size)
        start = outBufIdx = 0;

    outBufIdx = archtosword32(outBufIdx);
    outstart = archtosword32(start);
    outsize = archtosword16(size);

    lseek(compfp[testmt - 1].getFD(), idxoff, SEEK_SET);
    write(compfp[testmt - 1].getFD(), outBufIdx, 4);
    write(compfp[testmt - 1].getFD(), outstart, 4);
    write(compfp[testmt - 1].getFD(), outsize, 2);
    strcat(cacheBuf, buf);
}

private void flushCache()
{
    if (dirtyCache)
    {
        long idxoff;
        long start;
        long outstart;
        long size;
        long outsize;
        long zsize;
        long outzsize;

        idxoff = cacheBufIdx * 12;
        if (cacheBuf)
        {
            size = outsize = zsize = outzsize = strlen(cacheBuf);
            if (size)
            {
                //          if (compressor) {
                //              delete compressor;
                //              compressor = new LZSSCompress();
                //          }
                compressor - > Buf(cacheBuf);
                compressor - > zBuf(& zsize);
                outzsize = zsize;

                char * buf = new char[zsize * 2];
                memcpy(buf, compressor - > zBuf(& zsize), zsize);
                rawZFilter(buf, zsize, 1); // 1 = encipher

                start = outstart = lseek(textfp[cacheTestament - 1] - > getFd(), 0, SEEK_END);

                outstart = archtosword32(start);
                outsize = archtosword32(size);
                outzsize = archtosword32(zsize);

                write(textfp[cacheTestament - 1] - > getFd(), buf, zsize);

                delete[] buf;

                lseek(idxfp[cacheTestament - 1] - > getFd(), idxoff, SEEK_SET);
                write(idxfp[cacheTestament - 1] - > getFd(), & outstart, 4);
                write(idxfp[cacheTestament - 1] - > getFd(), & outzsize, 4);
                write(idxfp[cacheTestament - 1] - > getFd(), & outsize, 4);
            }
            free(cacheBuf);
            cacheBuf = 0;
        }
        dirtyCache = false;
    }
}
/**/
