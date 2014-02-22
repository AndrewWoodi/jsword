/**
 * Distribution License:
 * JSword is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License, version 2.1 or later
 * as published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The License is available on the internet at:
 *       http://www.gnu.org/copyleft/lgpl.html
 * or by writing to:
 *      Free Software Foundation, Inc.
 *      59 Temple Place - Suite 330
 *      Boston, MA 02111-1307, USA
 *
 * Copyright: 2013 - 2014
 *     The copyright to this program is held by it's authors.
 *
 */
package org.crosswire.jsword.versification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.crosswire.common.util.KeyValuePair;
import org.crosswire.common.util.LucidRuntimeException;
import org.crosswire.jsword.JSMsg;
import org.crosswire.jsword.passage.AbstractPassage;
import org.crosswire.jsword.passage.Key;
import org.crosswire.jsword.passage.KeyUtil;
import org.crosswire.jsword.passage.NoSuchKeyException;
import org.crosswire.jsword.passage.NoSuchVerseException;
import org.crosswire.jsword.passage.Passage;
import org.crosswire.jsword.passage.PassageKeyFactory;
import org.crosswire.jsword.passage.RestrictionType;
import org.crosswire.jsword.passage.SimpleOsisParser;
import org.crosswire.jsword.passage.Verse;
import org.crosswire.jsword.passage.VerseRange;
import org.crosswire.jsword.versification.system.Versifications;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Versification mapper allows you to a map a given verse to the KJV versification,
 * or unmap it from the KJV versification into your own versification.
 * <p>
 * A properties-like file will contain the non-KJV versification as they key, and the KJV versification value
 * as the target value... Duplicate keys are allowed.
 * </p>
 * <p>
 * i.e. Gen.1.1=Gen.1.2 means Gen.1.1 in X versification is Gen.1.2 in the KJV versification
 * </p>
 * <p>
 * You can specify a range on either side. If a range is present on both sides, they have to have the same number of
 * verses, i.e. verses are mapped verse by verse to each other<br/>
 * Gen.1.1-Gen.1.2=Gen.1.2-Gen.1.3 means Gen.1.1=Gen.1.2 and Gen.1.2=Gen.1.3<br/>
 *<br/>
 * Note: if the cardinality of the left & KJV sides are different by only one, the algorithm makes the
 * assumption that verse 0 should be disregarded in both ranges.
 * </p>
 * <p>
 * Mappings can be specified by offset. In this case, be aware this maps verse 0 as well. So for example:
 * </p>
 * <p>
 * Ps.19-Ps.20=-1 means Ps.19.0=Ps.18.50, Ps.19.1=Ps.19.0, Ps.19.2=Ps.19.1, etc.<br/>
 * It does not make much sense to have an offset for a single verse, so this is not supported.
 * Offsetting for multiple ranges however does, and operates range by range, i.e. each range is calculated separately.
 * Offsetting is somewhat equivalent to specifying ranges, and as a result, the verse 0 behaviour is identical.
 * </p>
 * <p>
 * You can use part-mappings. This is important if you want to preserve transformations from one side to another without
 * losing resolution of the verse.
 * </p>
 * <p>
 * For example,<br/>
 * if V1 defines Gen.1.1=Gen1.1, Gen1.2=Gen1.1<br/>
 * if V2 defines Gen.1.1=Gen1.1, Gen.1.2=Gen.1.1<br/>
 * then, mapping from V1=>KJV and KJV=>V2 gives you Gen.1.1=>Gen.1.1=>Gen.1.1-Gen.1.2 which is inaccurate if in fact
 * V1(Gen.1.1) actually equals V2(Gen.1.1). So instead, we use a split on the right hand-side:
 * </p>
 * <p>
 * For example,<br/>
 * V1 defines Gen.1.1=Gen1.1@a, Gen1.2=Gen1.1@b<br/>
 * V2 defines Gen.1.1=Gen1.1@a, Gen.1.2=Gen.1.1@b<br/>
 * then, mapping from V1=>KJV and KJV=>V2 gives you Gen.1.1=>Gen.1.1a=>Gen.1.1, which is now accurate.
 * A part is a string fragment placed after the end of a key reference. We cannot use # because that is commonly known
 * as a comment in real properties-file. Using a marker, means we can have meaningful part names if we so choose.
 * Parts of ranges are not supported.
 * </p>
 * <p>
 * Note: splits should never be seen by a user. The mapping from one versification to another is abstracted
 * such that the user can simply request the mapping between 2 verse (ranges).
 * </p>
 * <p>
 * Unmapped verses can be specified by inventing ids, either for whole sections, or verse by verse (this would
 * come in handy if two versifications have the same content, but the KJV doesn't). A section must be preceded
 * with a '?' character indicating that there will be no need to try and look up a reference.
 * Gen.1.1=?NewPassage
 * </p>
 * <p>
 * Since not specifying a verse mappings means there is a 1-2-1 unchanged mapping, we need a way of specifying
 * absent verses altogether:<br/>
 * ?=Gen1.1;Gen.1.5;<br/>
 * means that the non-KJV book simply does not contain verses Gen.1.1 and Gen.1.5 and therefore can't
 * be mapped.
 * </p>
 * <p>
 * We allow some global flags (one at present):<br/>
 * !zerosUnmapped : means that any mapping to or from a zero verse
 * </p>
 * <p>
 * TODO(CJB): think about whether when returning, we should clone, or make things immutable.
 * </p>
 * 
 * @see gnu.lgpl.License for license details.<br>
 *      The copyright to this program is held by it's authors.
 * @author chrisburrell
 */
public class VersificationToKJVMapper {
    public static final char PART_MARKER = '!';

    /**
     * @param mapping the mappings from one versification to another
     */
    public VersificationToKJVMapper(Versification nonKjv, final FileVersificationMapping mapping) {
        absentVerses = createEmptyPassage(KJV);
        toKJVMappings = new HashMap<Key, List<QualifiedKey>>();
        fromKJVMappings = new HashMap<QualifiedKey, Key>();
        this.nonKjv = nonKjv;
        processMappings(mapping);
        trace();
    }

    /**
     * This is the crux of the decoding facility.  The properties are expanded.
     *
     * @param mappings the input mappings, in a contracted, short-hand form
     */
    private void processMappings(FileVersificationMapping mappings) {
        final List<KeyValuePair> entries = mappings.getMappings();
        for (KeyValuePair entry : entries) {
            try {
                processEntry(entry);
            } catch (NoSuchKeyException ex) {
                // TODO(CJB): should we throw a config exception?
                LOGGER.error("Unable to process entry [{}] with value [{}]", entry.getKey(), entry.getValue(), ex);
                hasErrors = true;
            } catch (Exception ex) {
                // TODO(CJB): should we throw a config exception?
                LOGGER.error("Unable to process entry [{}] with value [{}]", entry.getKey(), entry.getValue(), ex);
                hasErrors = true;
            }
        }
    }

    private void processEntry(final KeyValuePair entry) throws NoSuchKeyException {
        String leftHand = entry.getKey();
        String kjvHand = entry.getValue();
        // TODO(CJB): Consider handling !zerosUnmapped here. It is the only place it can happen.
        
        // TODO(CJB): If ? can't be on left, consider directly calling
        // getExistingQualifiedKey(this.nonKjv, leftHand);
        QualifiedKey left = getRange(this.nonKjv, leftHand, null);

        // some global flag was specified, proceed to next key value pair.
        if (left == null) {
            return;
        }

        QualifiedKey kjv = getRange(KJV, kjvHand, left.getKey());
        addMappings(left, kjv);
    }

    /**
     * Adds a 1-Many mapping, by simply listing out all the properties. There is probably
     * a better way for storing this, perhaps in a tree - but for simplicity, we're going to list them out.
     *
     * @param leftHand  the left hand side operand
     * @param kjvVerses the verses that are mapped by the left hand side
     */
    private void addMappings(final QualifiedKey leftHand, final QualifiedKey kjvVerses) throws NoSuchVerseException {
        if (leftHand.getAbsentType() == QualifiedKey.Qualifier.ABSENT_IN_LEFT) {
            this.absentVerses.addAll(kjvVerses.getKey());
        } else if (leftHand.getKey().getCardinality() == 1) {
            add1ToManyMappings(leftHand.getKey(), kjvVerses);
        } else {
            addManyToMany(leftHand, kjvVerses);
        }
    }

    /**
     * Adds a many to many mapping, mappings all the verses on the left hand side to all the verses on the right hand side.
     * We support 2 types: Many-to-1 and Many-to-Many.
     *
     * @param leftHand is assumed to be many
     * @param kjvVerses could be 1 or many
     */
    // TODO(CJB): optimize getCardinality calls by using VerseRange and Verse as Key in QualifiedKey
    private void addManyToMany(final QualifiedKey leftHand, final QualifiedKey kjvVerses) {
        Iterator<Key> leftKeys = leftHand.getKey().iterator();

        boolean isKJVMany = kjvVerses.getAbsentType() != QualifiedKey.Qualifier.ABSENT_IN_KJV && kjvVerses.getKey().getCardinality() != 1;
        boolean skipVerse0 = false;
        Iterator<Key> kjvKeys = null;

        if (isKJVMany) {
            //we detect if the keys are 1-apart from each other. If so, then we skip verse 0 on both sides.
            int diff = Math.abs(leftHand.getKey().getCardinality() - kjvVerses.getKey().getCardinality());

            if (diff > 1) {
                reportCardinalityError(leftHand, kjvVerses);
            }
            skipVerse0 = diff == 1;
        }

        while (leftKeys.hasNext()) {
            final Key leftKey = leftKeys.next();

            if (isKJVMany) {
                if (skipVerse0 && ((Verse) leftKey).getVerse() == 0) {
                    continue;
                }

                if (kjvKeys == null) {
                    kjvKeys = kjvVerses.getKey().iterator();
                }

                if (!kjvKeys.hasNext()) {
                    reportCardinalityError(leftHand, kjvVerses);
                }

                Key nextKjvKey = kjvKeys.next();
                if (skipVerse0 && ((Verse) nextKjvKey).getVerse() == 0) {
                    nextKjvKey = kjvKeys.next();
                }

                QualifiedKey kjvKey = new QualifiedKey(nextKjvKey);
                addForwardMappingFromSingleKeyToRange(leftKey, kjvKey);
                addKJVToMapping(kjvKey, leftKey);

            } else {
                addForwardMappingFromSingleKeyToRange(leftKey, kjvVerses);
                addKJVToMapping(kjvVerses, leftKey);
            }
        }

        if (isKJVMany && kjvKeys.hasNext()) {
            reportCardinalityError(leftHand, kjvVerses);
        }
    }

    /**
     * If for some reason cardinalities of keys are different, we report it.
     *
     * @param leftHand  the left hand key
     * @param kjvVerses the kjv qualified key
     */
    private void reportCardinalityError(final QualifiedKey leftHand, final QualifiedKey kjvVerses) {
        // TODO (CJB): change this to a neater exception
        // then something went wrong, as we have remaining verses
        throw new LucidRuntimeException(String.format("%s has a cardinality of %d whilst %s has a cardinality of %d.",
                leftHand.getKey(), leftHand.getKey().getCardinality(),
                kjvVerses.getKey(), kjvVerses.getKey().getCardinality()));
    }

    /**
     * If leftKey is non-null (i.e. not attached to a simple specifier, then adds to the kjvTo mappings
     *
     * @param kjvVerses the kjv verses
     * @param leftKey   the left-hand key, possibly null.
     */
    private void addKJVToMapping(final QualifiedKey kjvVerses, final Key leftKey) {
        if (leftKey != null) {
            getNonEmptyKey(this.fromKJVMappings, kjvVerses).addAll(leftKey);

            //if we have a part, then we need to add the generified key as well...
            if (kjvVerses.getPart() != null) {
                getNonEmptyKey(this.fromKJVMappings, new QualifiedKey(kjvVerses.getKey())).addAll(leftKey);
            }
        }
    }

    /**
     * A simple two way entry between 2 1-1 entries.
     *
     * @param leftHand the verse on the left side, left is assumed to be 1 verse only
     * @param kjvHand  the KJV reference
     * @throws NoSuchVerseException
     */
    private void add1ToManyMappings(final Key leftHand, final QualifiedKey kjvHand) throws NoSuchVerseException {
        addForwardMappingFromSingleKeyToRange(leftHand, kjvHand);
        addReverse1ToManyMappings(leftHand, kjvHand);
    }

    /**
     * Adds the data into the reverse mappings. Caters for 1-2-1 and 1-2-Many mappings
     *
     * @param leftHand the reference of the left hand reference
     * @param kjvHand  the kjv reference/key, qualified with the part
     */
    private void addReverse1ToManyMappings(final Key leftHand, final QualifiedKey kjvHand) {
        //add the reverse mapping, for 1-1 mappings
        if (kjvHand.getAbsentType() == QualifiedKey.Qualifier.ABSENT_IN_KJV || kjvHand.getKey().getCardinality() == 1) {
            // TODO(CJB): deal with parts
            addKJVToMapping(kjvHand, leftHand);
        } else {
            //add the 1-many mappings
            //expand the key and add them all
            //Parts are not supported on ranges...
            Iterator<Key> kjvKeys = kjvHand.getKey().iterator();
            while (kjvKeys.hasNext()) {
                addKJVToMapping(new QualifiedKey(kjvKeys.next()), leftHand);
            }
        }
    }

    /**
     * Adds a forward mappings from left to KJV.
     *
     * @param leftHand the left hand reference (corresponding to a non-kjv versification)
     * @param kjvHand  the kjv reference (with part if applicable).
     */
    private void addForwardMappingFromSingleKeyToRange(final Key leftHand, final QualifiedKey kjvHand) {
        if (leftHand == null) {
            return;
        }

        getNonEmptyMappings(this.toKJVMappings, leftHand).add(kjvHand);
    }

    /**
     * Gets a non-empty key list, either new or the one existing in the map already.
     *
     * @param mappings the map from key to list of values
     * @param key      the key
     * @return the non-empty mappings list
     */
    private Key getNonEmptyKey(final Map<QualifiedKey, Key> mappings, final QualifiedKey key) {
        Key matchingVerses = mappings.get(key);
        if (matchingVerses == null) {
            matchingVerses = createEmptyPassage(this.nonKjv);
            mappings.put(key, matchingVerses);
        }
        return matchingVerses;
    }

    /**
     * Gets a non-empty list, either new or the one existing in the map already
     *
     * @param mappings the map from key to list of values
     * @param key      the key
     * @param <T>      the type of the key
     * @param <S>      the type of the value
     * @return the separate list of verses
     */
    private <T, S> List<S> getNonEmptyMappings(final Map<T, List<S>> mappings, final T key) {
        List<S> matchingVerses = mappings.get(key);
        if (matchingVerses == null) {
            matchingVerses = new ArrayList<S>();
            mappings.put(key, matchingVerses);
        }
        return matchingVerses;
    }

    /**
     * Gets the input range as a single verse or throws an exception
     *
     * @param verseKey the verses
     * @return the separate list of verses
     */
    // TODO(CJB): Consider eliminating this by replacing where it is used with a Verse parameter
    private QualifiedKey getRangeAsVerse(final Versification versification, String verseKey) throws NoSuchKeyException {
        final QualifiedKey range = getRange(versification, verseKey, null);
        Key encapsulatedRange = range.getKey();
        if (encapsulatedRange != null) {
            final Iterator<Key> keyIterator = encapsulatedRange.iterator();
            //get first key
            if (!keyIterator.hasNext()) {
                throw new UnsupportedOperationException("Attempting to resolve an empty range. Only single verse look-ups are supported.");
            }

            range.setKey(KeyUtil.getVerse(keyIterator.next()));
            if (keyIterator.hasNext()) {
                throw new UnsupportedOperationException("Attempting to resolve more than 1 verse at a time. Only single verse look-ups are supported.");
            }
        }

        return range;
    }

    /**
     * Expands a reference to all its verses
     *
     * @param versesKey the verses
     * @return the separate list of verses
     */
    private QualifiedKey getRange(final Versification versification, String versesKey, Key offsetBasis) throws NoSuchKeyException {
        //deal with absent keys in left & absent keys in right, which are simply marked by a '?'
        if (versesKey == null || versesKey.length() == 0) {
            throw new NoSuchKeyException(JSMsg.gettext("Cannot understand [{0}] as a chapter or verse.", versesKey));
        }

        // TODO(CJB): maybe move this up to processEntry
        // we allow some global flags properties - for a want of a better syntax!
        if ("!zerosUnmapped".equals(versesKey)) {
            this.zerosUnmapped = true;
            return null;
        }

        char firstChar = versesKey.charAt(0);
        switch (firstChar) {
            case '?':
                // TODO(CJB): The class JavaDoc has ? on the left side
                // Where is that in any of the mapping code.
                return getAbsentQualifiedKey(versification, versesKey);
            case '+':
            case '-':
                // TODO(CJB): Is + or - allowed only on the right hand side
                return getOffsetQualifiedKey(versification, versesKey, offsetBasis);
            default:
                return getExistingQualifiedKey(versification, versesKey);
        }
    }

    /**
     * Deals with offset markers, indicating a passage is +x or -x verses from this one.
     *
     * @param versification the versification of the passed in key
     * @param versesKey     the text of the reference we are trying to parse
     * @return the qualified key representing this
     */
    private QualifiedKey getOffsetQualifiedKey(final Versification versification, final String versesKey, Key offsetBasis) throws NoSuchKeyException {
        if (offsetBasis == null || offsetBasis.getCardinality() == 0) {
            // TODO(CJB): internationalize
            throw new NoSuchKeyException(JSMsg.gettext("Unable to offset the given key [{0}]", offsetBasis));
        }
        int offset = Integer.parseInt(versesKey.substring(1));

        // Convert key immediately to the our target versification system, namely the KJV, since it is the only
        // one supported. Convert by ref - since the whole purpose of this is to define equivalents.

        // TODO(CJB): Optimize. Don't convert to a string and back. Both are expensive.
        // I do realize this code is not currently used.
        //    Basically, the left hand side, offsetBasis, is a verse or a range given by start and end osisIDs.
        //    A VerseRange actually is a start Verse and a cardinality, possibly 1.
        //    So the start verse needs to be located in the new versification and then incremented/decremented
        //    See the Versification class for increment/decrement.
        // Something like (untested):
         VerseRange vr = null;
         if (offsetBasis instanceof VerseRange) {
             vr = (VerseRange) offsetBasis;
         } else if (offsetBasis instanceof Passage) {
             Iterator<VerseRange> iter = ((Passage) offsetBasis).rangeIterator(RestrictionType.NONE);
             if (iter.hasNext()) {
                 vr = (VerseRange) iter.next();
             }
         }
         if (vr == null) {
             // TODO(CJB): internationalize
             throw new NoSuchKeyException(JSMsg.gettext("Unable to offset the given key [{0}]", offsetBasis));
         }

         Verse vrStart = vr.getStart();
         Verse start = new Verse(versification, vrStart.getBook(), vrStart.getChapter(), vrStart.getVerse());
         // While you can add a negative number, these are optimized for their operation
         if (offset < 0) {
             start = versification.subtract(start, -offset);
         } else if (offset > 0) {
             start = versification.add(start, offset);
         }
         Verse end = start;
         if (vr.getCardinality() > 1) {
             end = versification.add(start, vr.getCardinality() - 1);
         }
         VerseRange newvr = new VerseRange(versification, start, end);
         return new QualifiedKey(KeyUtil.getPassage(newvr));
//        QualifiedKey approximateQualifiedKey = this.getExistingQualifiedKey(versification, offsetBasis.getOsisID());
//        Key approximateKey = approximateQualifiedKey.getKey();
//
//        //we now need to apply the offset to our key... So if it's a negative offset, we need to add the keys
//        //that occur before the key and therefore blur the key
//        Key newKey = null;
//        if (approximateKey instanceof VerseRange) {
//            newKey = getNewVerseRange(versification, offset, (VerseRange) approximateKey);
//        } else if (approximateKey instanceof AbstractPassage) {
//            Iterator<Key> rangeIterator = ((AbstractPassage) approximateKey).rangeIterator(RestrictionType.NONE);
//            newKey = createEmptyPassage(versification);
//            while (rangeIterator.hasNext()) {
//                final Key nextInRange = rangeIterator.next();
//                if (nextInRange instanceof VerseRange) {
//                    newKey.addAll(getNewVerseRange(versification, offset, (VerseRange) nextInRange));
//                } else {
//                    throw new UnsupportedOperationException("Not sure how to parse key of type: "
//                            + nextInRange.getClass() + " found in range " + nextInRange);
//                }
//            }
//        }
//        approximateQualifiedKey.setKey(newKey);
//
//        //no longer approximate
//        return approximateQualifiedKey;
    }

    private VerseRange getNewVerseRange(final Versification versification, final int offset, final VerseRange verseRange) {
        // TODO(CJB): See comment in previous method for a better way.
        // The Versification class has optimized methods to compute a verse offset from another.
        final Verse newStart = new Verse(versification, verseRange.getStart().getOrdinal() + offset);
        final Verse newEnd = new Verse(versification, verseRange.getEnd().getOrdinal() + offset);
        return new VerseRange(versification, newStart, newEnd);
    }

    /**
     * Deals with real keys found in the versification.
     *
     * @param versification the versification of the passed in key
     * @param versesKey     the text of the reference we are trying to parse
     * @return the qualified key representing this
     */
    private QualifiedKey getExistingQualifiedKey(final Versification versification, final String versesKey) throws NoSuchKeyException {
        //if we have a part, then we extra it. Unless we're mapping whole books, a single alpha character has to signify a part.
        String reference = versesKey;
        String part = null;
        int indexOfPart = versesKey.lastIndexOf(PART_MARKER);
        if (indexOfPart != -1) {
            // BUG(CJB): Synodal has: 1Kgs.18.34=1Kgs.18.33!b-1Kgs.18.34
            // This will create a reference of 1Kgs.18.33, dropping 1Kgs.18.34
            // Maybe substring the part with a length of 2
            // Maybe remove 2 chars to create reference
            // Would there ever be something like ...=yyy!b-xxx!a
            // which splits both the start and the end verses?
            reference = reference.substring(0, indexOfPart);
            part = versesKey.substring(indexOfPart);
        }
        // TODO(CJB): Use a VerseRange rather than a default Passage or a RangedPassage
        // Note the following code will also take a single verse and make it into a VerseRange of cardinality 1
        // E.g.
        //return new QualifiedKey(VerseRangeFactory.fromString(versification, reference), part);
        VerseRange vr = SimpleOsisParser.parseOsisRef(versification, reference);
        return new QualifiedKey(KeyUtil.getPassage(vr), part);
    }

    /**
     * Deals with absent markers, whether absent in the KJV or absent in the current versification.
     *
     * @param versification the versification of the passed in key
     * @param versesKey     the text of the reference we are trying to parse
     * @return the qualified key representing this
     */
    private QualifiedKey getAbsentQualifiedKey(final Versification versification, final String versesKey) {
        if (versification.equals(this.nonKjv)) {
            // we're dealing with a '?', and therefore an ABSENT_IN_LEFT scenarios.
            // we do not support any other ? markers on the left
            return new QualifiedKey(QualifiedKey.Qualifier.ABSENT_IN_LEFT);
        }
        // we're dealing with a ? on the KJV side, therefore we must be looking at
        // a section name
        return new QualifiedKey(versesKey);
    }


    /**
     * Converts the input to the KJV versification
     *
     * @return the equivalent key
     */
    public String map(final String key) throws NoSuchKeyException {
        // TODO(CJB): Consider changing the parameter to Verse key.
        // Converting a Verse to a string is expensive.
        // Parsing the string is expensive.
        final QualifiedKey range = getRangeAsVerse(nonKjv, key);
        // TODO(CJB): Consider changing return type to a VerseRange or a Verse
        // Converting a Passage, VerseRange or Verse to a string is expensive
        return map((Verse) range.getKey()).getOsisRef();
    }


    /**
     * @return the qualified keys associated with the input key.
     */
    private List<QualifiedKey> getQualifiedKeys(final Key leftKey) {
        return this.toKJVMappings.get(leftKey);
    }

    /**
     * Returns the key in the target versification, by using the OsisRef. Note: if the key doesn't exist
     * in the other versification, it is most probably because that key doesn't exist at all. So we'll log a warning,
     * but return an empty key.
     * <p>
     * There is one exception however, and that is, for Versifications that don't use verse 0s we allow a global
     * flag to prevent mappings for verse 0.
     * </p>
     *
     * @param qualifiedKey the qualified key containing the OSIS key ref.
     * @return the same key represented by the OSIS ref, except that it is the target versification.
     */
    private QualifiedKey getKeyRefInDifferentVersification(final QualifiedKey qualifiedKey, Versification target) {
        try {
            if (this.zerosUnmapped && isZero(qualifiedKey)) {
                return new QualifiedKey(createEmptyPassage(target));
            }
            return new QualifiedKey(PassageKeyFactory.instance().getKey(target, qualifiedKey.getKey().getOsisRef()));
        } catch (NoSuchKeyException ex) {
            LOGGER.warn("Unable to transfer key contents [{}] to versification [{}]", qualifiedKey.getKey().getOsisRef(), target.getName());
            return new QualifiedKey(createEmptyPassage(target));
        }
    }

    /**
     * Qualified key to test for a verse 0
     * @param qualifiedKey the qualified key
     * @return true, if the qualified key represents verse 0
     */
    private boolean isZero(final QualifiedKey qualifiedKey) {
        Key k = qualifiedKey.getKey();
        if (k == null) {
            return false;
        }

        Iterator<Key> keys = k.iterator();
        if (keys.hasNext() && ((Verse) keys.next()).getVerse() == 0) {
            // true if we don't have any more keys in our set
            return !keys.hasNext();
        }

        //no keys in iterator
        return false;
    }


    /**
     * Converts the input to the KJV versification, but returns the qualified key representation, i.e. not necessarily
     * an OSIS representation. The key needs to represent a single verse
     * <p>
     * This key is useful if different versifications (say Dan 3 in the 2 Catholic versifications) have the same
     * section which is not present in the KJV versification.
     * </p>
     * <p>
     * Its sister method, taking in a Key, and returning a QualifiedKey will be more helpful, generally speaking
     * </p>
     * 
     * @return the equivalent key, which may or may not be used to look up a reference in a book.
     */
    public String mapToQualifiedKey(final String verseKey) throws NoSuchKeyException {
        // TODO(CJB): Consider changing the parameter to Verse key.
        // Converting a Verse to a string is expensive.
        // Parsing the string is expensive.
        final QualifiedKey qualifiedVerse = getRangeAsVerse(nonKjv, verseKey);
        List<QualifiedKey> qualifiedKeys = map(qualifiedVerse);

        StringBuilder representation = new StringBuilder(128);
        for (int i = 0; i < qualifiedKeys.size(); i++) {
            final QualifiedKey qk = qualifiedKeys.get(i);
            representation.append(qk.getAbsentType() == QualifiedKey.Qualifier.ABSENT_IN_KJV ? qk.getSectionName() : qk.getKey().getOsisRef());

            if (qk.getPart() != null) {
                representation.append(qk.getPart());
            }

            if (i < qualifiedKeys.size() - 1) {
                representation.append(' ');
            }
        }
        return representation.toString();
    }

    /**
     * Maps the full qualified key to its proper equivalent in the KJV.
     *
     * @param qualifiedKey the qualified key
     * @return the list of matching qualified keys in the KJV versification.
     */
    public List<QualifiedKey> map(QualifiedKey qualifiedKey) {
        if (qualifiedKey.getKey() != null) {
            List<QualifiedKey> kjvKeys = getQualifiedKeys(qualifiedKey.getKey());
            if (kjvKeys == null || kjvKeys.size() == 0) {
                //then we found no mapping, so we're essentially going to return the same key back...
                //unless it's a verse 0 and then we'll check the global flag.
                kjvKeys = new ArrayList<QualifiedKey>();
                kjvKeys.add(getKeyRefInDifferentVersification(qualifiedKey, KJV));
                return kjvKeys;
            }
            return kjvKeys;
        }

        return new ArrayList<QualifiedKey>();
    }

    /**
     * Converts the input to the KJV versification
     *
     * @return the equivalent key
     */
    public Key map(final Verse leftKey) {
        List<QualifiedKey> qualifiedKeys = map(new QualifiedKey(leftKey));

        //convert qualified keys into a passage representation, since that's what the user is after.
        Passage keyList = createEmptyPassage(KJV);
        for (QualifiedKey qualifiedKey : qualifiedKeys) {
            //we may bits in here, that don't exist in the KJV
            if (qualifiedKey.getKey() != null) {
                keyList.addAll(qualifiedKey.getKey());
            }
        }

        return keyList;
    }

    /**
     * Converts a KJV verse to the target versification
     *
     * @return the key in the left-hand versification
     */
    public String unmap(final String kjvVerse) throws NoSuchKeyException {
        // TODO(CJB): Consider changing the parameter to Verse key.
        // Converting a Verse to a string is expensive.
        // Parsing the string is expensive.
        // TODO(CJB): Consider changing return type to a VerseRange or a Verse
        // Converting a Passage, VerseRange or Verse to a string is expensive
        return unmap(getRangeAsVerse(KJV, kjvVerse)).getOsisRef();
    }

    /**
     * Converts a KJV verse to the target versification, from a qualified key, rather than a real key
     *
     * @return the key in the left-hand versification
     */
    public Key unmap(final QualifiedKey kjvVerse) {
        // TODO(CJB): cope for parts?
        Key left = this.fromKJVMappings.get(kjvVerse);

        if (left == null && kjvVerse.getPart() != null) {
            // Try again, but without the part this time
            QualifiedKey genericKjvVerse = new QualifiedKey(kjvVerse.getKey());
            left = this.fromKJVMappings.get(genericKjvVerse);
        }


        //if we have no mapping, then we are in 1 of two scenarios
        //the verse is either totally absent, or the verse is not part of the mappings, meaning it is a straight map
        if (left == null) {
            return this.absentVerses.contains(kjvVerse.getKey()) ? createEmptyPassage(KJV) : this.getKeyRefInDifferentVersification(kjvVerse, this.nonKjv).getKey();
        }
        return left;
    }

    /**
     * Converts a KJV verse to the target versification
     *
     * @return the key in the left-hand versification
     */
    public Key unmap(final Key kjvVerse) {
        return unmap(new QualifiedKey(kjvVerse));
    }

    /**
     * Outputs the mappings for debug purposes...
     */
    public void trace() {
        if (!LOGGER.isTraceEnabled()) {
            return;
        }

        LOGGER.trace("******************************");
        LOGGER.trace("Forward mappings towards KJV");
        LOGGER.trace("******************************");
        for (Map.Entry<Key, List<QualifiedKey>> entry : this.toKJVMappings.entrySet()) {
            List<QualifiedKey> kjvVerses = entry.getValue();
            for (QualifiedKey q : kjvVerses) {
                LOGGER.trace("\t({}) {} => {}{}{} (KJV)",
                        this.nonKjv.getName(),
                        entry.getKey().getOsisRef(),
                        q.getKey() != null ? q.getKey().getOsisRef() : "",
                        q.getPart() != null ? q.getPart() : "",
                        getStringAbsentType(q));
            }
        }

        LOGGER.trace("Absent verses in left versification: [{}]", this.absentVerses.getOsisRef());
        LOGGER.trace("******************************");
        LOGGER.trace("Backwards mappings from KJV");
        LOGGER.trace("******************************");
        for (Map.Entry<QualifiedKey, Key> entry : this.fromKJVMappings.entrySet()) {
            LOGGER.trace("(KJV): {}{} => {} ({})",
                    entry.getKey().getKey() != null ? entry.getKey().getKey().getOsisRef() : "",
                    getStringAbsentType(entry.getKey()),
                    entry.getValue().getOsisRef(),
                    this.nonKjv.getName());
        }
        LOGGER.trace("##############################");
    }

    /**
     * A string printable version of absent type held in the qualified key
     *
     * @param q the qualified key
     * @return the printable form of the absent type.
     */
    private String getStringAbsentType(QualifiedKey q) {
        String absentType;
        switch (q.getAbsentType()) {
            case ABSENT_IN_KJV:
                absentType = q.getSectionName();
                break;
            case ABSENT_IN_LEFT:
                absentType = "Absent in Left";
                break;
            default:
                absentType = "";
                break;
        }
        return absentType;
    }

    /**
     * Returns whether we initialised with errors
     */
    boolean hasErrors() {
        return hasErrors;
    }

    /** Simplify creation of an empty passage object of the default type, with the required v11n.
     * 
     * @param versification required v11n for new Passage
     * @return              empty Passage
     */
    private Passage createEmptyPassage(Versification versification) {
        return PassageKeyFactory.getDefaultType().createEmptyPassage(versification);
    }

    /* the 'from' or 'left' versification */
    private Versification nonKjv;

    /* the absent verses, i.e. those present in the KJV, but not in the left versification */
    private Key absentVerses;
    private Map<Key, List<QualifiedKey>> toKJVMappings;
    private Map<QualifiedKey, Key> fromKJVMappings;
    private boolean zerosUnmapped;
    private boolean hasErrors;

    private static final Versification KJV = Versifications.instance().getVersification(Versifications.DEFAULT_V11N);
    private static final Logger LOGGER = LoggerFactory.getLogger(VersificationToKJVMapper.class);
}
