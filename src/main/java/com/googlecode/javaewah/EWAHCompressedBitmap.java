package com.googlecode.javaewah;

import com.googlecode.javaewah.symmetric.RunningBitmapMerge;
import com.googlecode.javaewah.symmetric.ThresholdFuncBitmap;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * <p>
 * This implements the patent-free(1) EWAH scheme. Roughly speaking, it is a
 * 64-bit variant of the BBC compression scheme used by Oracle for its bitmap
 * indexes.
 * </p>
 * 
 * <p>
 * The objective of this compression type is to provide some compression, while
 * reducing as much as possible the CPU cycle usage.
 * </p>
 * 
 * <p>
 * Once constructed, the bitmap is essentially immutable (unless you call the
 * "set" or "add" methods). Thus, it can be safely used in multi-threaded
 * programs.
 * </p>
 * 
 * <p>
 * This implementation being 64-bit, it assumes a 64-bit CPU together with a
 * 64-bit Java Virtual Machine. This same code on a 32-bit machine may not be as
 * fast. There is also a 32-bit version of this code in the class
 * javaewah32.EWAHCompressedBitmap32.
 * </p>
 * 
 * <p>
 * Here is a code sample to illustrate usage:
 * </p>
 * 
 * <pre>
 * EWAHCompressedBitmap ewahBitmap1 = EWAHCompressedBitmap.bitmapOf(0, 2, 55, 64,
 *         1 &lt;&lt; 30);
 * EWAHCompressedBitmap ewahBitmap2 = EWAHCompressedBitmap.bitmapOf(1, 3, 64,
 *         1 &lt;&lt; 30);
 * EWAHCompressedBitmap ewahBitmap3 = EWAHCompressedBitmap
 *         .bitmapOf(5, 55, 1 &lt;&lt; 30);
 * EWAHCompressedBitmap ewahBitmap4 = EWAHCompressedBitmap
 *         .bitmapOf(4, 66, 1 &lt;&lt; 30);
 * EWAHCompressedBitmap orbitmap = ewahBitmap1.or(ewahBitmap2);
 * EWAHCompressedBitmap andbitmap = ewahBitmap1.and(ewahBitmap2);
 * EWAHCompressedBitmap xorbitmap = ewahBitmap1.xor(ewahBitmap2);
 * andbitmap = EWAHCompressedBitmap.and(ewahBitmap1, ewahBitmap2, ewahBitmap3,
 *         ewahBitmap4);
 * ByteArrayOutputStream bos = new ByteArrayOutputStream();
 * ObjectOutputStream oo = new ObjectOutputStream(bos);
 * ewahBitmap1.writeExternal(oo);
 * oo.close();
 * ewahBitmap1 = null;
 * ewahBitmap1 = new EWAHCompressedBitmap();
 * ByteArrayInputStream bis = new ByteArrayInputStream(bos.toByteArray());
 * ewahBitmap1.readExternal(new ObjectInputStream(bis));
 * EWAHCompressedBitmap threshold2 = EWAHCompressedBitmap.threshold(2,
 *         ewahBitmap1, ewahBitmap2, ewahBitmap3, ewahBitmap4);
 * </pre>
 * <p>
 * For more details, see the following papers:
 * </p>
 * 
 * <ul>
 * <li>Daniel Lemire, Owen Kaser, Kamel Aouiche, Sorting improves word-aligned
 * bitmap indexes. Data &amp; Knowledge Engineering 69 (1), pages 3-28, 2010. <a
 * href="http://arxiv.org/abs/0901.3751">http://arxiv.org/abs/0901.3751</a></li>
 * <li> Owen Kaser and Daniel Lemire, Compressed bitmap indexes: beyond unions and intersections
 * <a href="http://arxiv.org/abs/1402.4466">http://arxiv.org/abs/1402.4466</a></li>
 * </ul>
 * 
 * <p>
 * A 32-bit version of the compressed format was described by Wu et al. and
 * named WBC:
 * </p>
 * 
 * <ul>
 * <li>K. Wu, E. J. Otoo, A. Shoshani, H. Nordberg, Notes on design and
 * implementation of compressed bit vectors, Tech. Rep. LBNL/PUB-3161, Lawrence
 * Berkeley National Laboratory, available from http://crd.lbl.
 * gov/~kewu/ps/PUB-3161.html (2001).</li>
 * </ul>
 * 
 * <p>
 * Probably, the best prior art is the Oracle bitmap compression scheme (BBC):
 * </p>
 * <ul>
 * <li>G. Antoshenkov, Byte-Aligned Bitmap Compression, DCC'95, 1995.</li>
 * </ul>
 * 
 * <p>
 * 1- The authors do not know of any patent infringed by the following
 * implementation. However, similar schemes, like WAH are covered by patents.
 * </p>
 *
 * @see com.googlecode.javaewah32.EWAHCompressedBitmap32 EWAHCompressedBitmap32
 * @since 0.1.0
 */
public final class EWAHCompressedBitmap implements Cloneable, Externalizable, Iterable<Integer>, BitmapStorage, LogicalElement<EWAHCompressedBitmap> {

    /**
     * Creates an empty bitmap (no bit set to true).
     */
    public EWAHCompressedBitmap() {
        this(DEFAULT_BUFFER_SIZE);
    }

    /**
     * Sets explicitly the buffer size (in 64-bit words). The initial memory
     * usage will be "bufferSize * 64". For large poorly compressible
     * bitmaps, using large values may improve performance.
     *
     * @param bufferSize number of 64-bit words reserved when the object is
     *                   created)
     */
    public EWAHCompressedBitmap(final int bufferSize) {
        this.buffer = new long[bufferSize];
        this.rlw = new RunningLengthWord(this, 0);
    }

    /**
     * @param newData the word
     * @deprecated use addWord() instead.
     */
    @Deprecated
    public void add(final long newData) {
        addWord(newData);
    }

    /**
     * @param newData        the word
     * @param bitsThatMatter the number of significant bits (by default it should
     *                       be 64)
     * @deprecated use addWord() instead.
     */
    @Deprecated
    public void add(final long newData, final int bitsThatMatter) {
        addWord(newData, bitsThatMatter);
    }

    /**
     * Adding words directly to the bitmap (for expert use).
     * 
     * This method adds bits in words of 4*8 bits. It is not to
     * be confused with the set method which sets individual bits.
     * 
     * Most users will want the set method.
     * 
     * Example: if you add word 321 to an empty bitmap, you are have
     * added (in binary notation) 0b101000001, so you have effectively
     * called set(0), set(6), set(8) in sequence.
     * 
     * Since this modifies the bitmap, this method is not thread-safe.
     * 
     * API change: prior to version 0.8.3, this method was called add.
     *
     * @param newData the word
     */
    @Override
    public void addWord(final long newData) {
        addWord(newData, WORD_IN_BITS);
    }

    /**
     * Adding words directly to the bitmap (for expert use).
     * Since this modifies the bitmap, this method is not thread-safe.
     * 
     * API change: prior to version 0.8.3, this method was called add.
     *
     * @param newData        the word
     * @param bitsThatMatter the number of significant bits (by default it should
     *                       be 64)
     */
    public void addWord(final long newData, final int bitsThatMatter) {
        this.sizeInBits += bitsThatMatter;
        if (newData == 0) {
            addEmptyWord(false);
        } else if (newData == ~0l) {
            addEmptyWord(true);
        } else {
            addLiteralWord(newData);
        }
    }

    /**
     * For internal use.
     *
     * @param v the boolean value
     */
    private void addEmptyWord(final boolean v) {
        final boolean noLiteralWords = (this.rlw.getNumberOfLiteralWords() == 0);
        final long runningLength = this.rlw.getRunningLength();
        if (noLiteralWords && runningLength == 0) {
            this.rlw.setRunningBit(v);
        }
        if (noLiteralWords && this.rlw.getRunningBit() == v && (runningLength < RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT)) {
            this.rlw.setRunningLength(runningLength + 1);
            return;
        }
        push_back(0);
        this.rlw.position = this.actualSizeInWords - 1;
        this.rlw.setRunningBit(v);
        this.rlw.setRunningLength(1);
    }

    /**
     * For internal use.
     *
     * @param newData the literal word
     */
    private void addLiteralWord(final long newData) {
        final int numberSoFar = this.rlw.getNumberOfLiteralWords();
        if (numberSoFar >= RunningLengthWord.LARGEST_LITERAL_COUNT) {
            push_back(0);
            this.rlw.position = this.actualSizeInWords - 1;
            this.rlw.setNumberOfLiteralWords(1);
            push_back(newData);
        }
        this.rlw.setNumberOfLiteralWords(numberSoFar + 1);
        push_back(newData);
    }

    /**
     * if you have several literal words to copy over, this might be faster.
     * 
     * Since this modifies the bitmap, this method is not thread-safe.
     *
     * @param data   the literal words
     * @param start  the starting point in the array
     * @param number the number of literal words to add
     */
    @Override
    public void addStreamOfLiteralWords(final long[] data, final int start, final int number) {
        int leftOverNumber = number;
        while (leftOverNumber > 0) {
            final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
            final int whatWeCanAdd = leftOverNumber < RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords ? leftOverNumber : RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords;
            this.rlw.setNumberOfLiteralWords(numberOfLiteralWords + whatWeCanAdd);
            leftOverNumber -= whatWeCanAdd;
            push_back(data, start, whatWeCanAdd);
            this.sizeInBits += whatWeCanAdd * WORD_IN_BITS;
            if (leftOverNumber > 0) {
                push_back(0);
                this.rlw.position = this.actualSizeInWords - 1;
            }
        }
    }

    /**
     * For experts: You want to add many zeroes or ones? This is the method
     * you use.
     * 
     * Since this modifies the bitmap, this method is not thread-safe.
     *
     * @param v      the boolean value
     * @param number the number
     */
    @Override
    public void addStreamOfEmptyWords(final boolean v, long number) {
        if (number == 0)
            return;
        this.sizeInBits += number * WORD_IN_BITS;
        fastaddStreamOfEmptyWords(v, number);
    }

    /**
     * Same as addStreamOfLiteralWords, but the words are negated.
     * 
     * Since this modifies the bitmap, this method is not thread-safe.
     *
     * @param data   the literal words
     * @param start  the starting point in the array
     * @param number the number of literal words to add
     */
    @Override
    public void addStreamOfNegatedLiteralWords(final long[] data, final int start, final int number) {
        int leftOverNumber = number;
        while (leftOverNumber > 0) {
            final int numberOfLiteralWords = this.rlw.getNumberOfLiteralWords();
            final int whatWeCanAdd = leftOverNumber < RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords ? leftOverNumber : RunningLengthWord.LARGEST_LITERAL_COUNT - numberOfLiteralWords;
            this.rlw.setNumberOfLiteralWords(numberOfLiteralWords + whatWeCanAdd);
            leftOverNumber -= whatWeCanAdd;
            negative_push_back(data, start, whatWeCanAdd);
            this.sizeInBits += whatWeCanAdd * WORD_IN_BITS;
            if (leftOverNumber > 0) {
                push_back(0);
                this.rlw.position = this.actualSizeInWords - 1;
            }
        }
    }

    /**
     * Returns a new compressed bitmap containing the bitwise AND values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     * @since 0.4.3
     */
    @Override
    public EWAHCompressedBitmap and(final EWAHCompressedBitmap a) {
        int size = this.actualSizeInWords > a.actualSizeInWords ? this.actualSizeInWords : a.actualSizeInWords;
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
        andToContainer(a, container);
        return container;
    }

    /**
     * Computes new compressed bitmap containing the bitwise AND values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * The current bitmap is not modified.
     * 
     * The content of the container is overwritten.
     *
     * @param a         the other bitmap (it will not be modified)
     * @param container where we store the result
     * @since 0.4.0
     */
    public void andToContainer(final EWAHCompressedBitmap a, final BitmapStorage container) {
        container.clear();
        final EWAHIterator i = a.getEWAHIterator();
        final EWAHIterator j = getEWAHIterator();
        final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
        final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
        while ((rlwi.size() > 0) && (rlwj.size() > 0)) {
            while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
                final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
                final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
                final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
                if (!predator.getRunningBit()) {
                    container.addStreamOfEmptyWords(false, predator.getRunningLength());
                    prey.discardFirstWords(predator.getRunningLength());
                } else {
                    final long index = prey.discharge(container, predator.getRunningLength());
                    container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
                }
                predator.discardRunningWords();
            }
            final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
            if (nbre_literal > 0) {
                for (int k = 0; k < nbre_literal; ++k) {
                    container.addWord(rlwi.getLiteralWordAt(k) & rlwj.getLiteralWordAt(k));
                }
                rlwi.discardFirstWords(nbre_literal);
                rlwj.discardFirstWords(nbre_literal);
            }
        }
        if (ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING) {
            final boolean i_remains = rlwi.size() > 0;
            final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
            remaining.dischargeAsEmpty(container);
            container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
        }
    }

    /**
     * Returns the cardinality of the result of a bitwise AND of the values
     * of the current bitmap with some other bitmap. Avoids
     * allocating an intermediate bitmap to hold the result of the OR.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the cardinality
     * @since 0.4.0
     */
    public int andCardinality(final EWAHCompressedBitmap a) {
        final BitCounter counter = new BitCounter();
        andToContainer(a, counter);
        return counter.getCount();
    }

    /**
     * Returns a new compressed bitmap containing the bitwise AND NOT values
     * of the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     */
    @Override
    public EWAHCompressedBitmap andNot(final EWAHCompressedBitmap a) {
        int size = this.actualSizeInWords > a.actualSizeInWords ? this.actualSizeInWords : a.actualSizeInWords;
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
        andNotToContainer(a, container);
        return container;
    }

    /**
     * Returns a new compressed bitmap containing the bitwise AND NOT values
     * of the current bitmap with some other bitmap. This method is expected
     * to be faster than doing A.and(B.clone().not()).
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * The current bitmap is not modified.
     * 
     * The content of the container is overwritten.
     *
     * @param a         the other bitmap (it will not be modified)
     * @param container where to store the result
     * @since 0.4.0
     */
    public void andNotToContainer(final EWAHCompressedBitmap a, final BitmapStorage container) {
        container.clear();
        final EWAHIterator i = getEWAHIterator();
        final EWAHIterator j = a.getEWAHIterator();
        final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
        final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
        while ((rlwi.size() > 0) && (rlwj.size() > 0)) {
            while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
                final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
                final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
                final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
                if (((predator.getRunningBit()) && (i_is_prey)) || ((!predator.getRunningBit()) && (!i_is_prey))) {
                    container.addStreamOfEmptyWords(false, predator.getRunningLength());
                    prey.discardFirstWords(predator.getRunningLength());
                } else if (i_is_prey) {
                    final long index = prey.discharge(container, predator.getRunningLength());
                    container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
                } else {
                    final long index = prey.dischargeNegated(container, predator.getRunningLength());
                    container.addStreamOfEmptyWords(true, predator.getRunningLength() - index);
                }
                predator.discardRunningWords();
            }
            final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
            if (nbre_literal > 0) {
                for (int k = 0; k < nbre_literal; ++k) container.addWord(rlwi.getLiteralWordAt(k) & (~rlwj.getLiteralWordAt(k)));
                rlwi.discardFirstWords(nbre_literal);
                rlwj.discardFirstWords(nbre_literal);
            }
        }
        final boolean i_remains = rlwi.size() > 0;
        final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
        if (i_remains)
            remaining.discharge(container);
        else if (ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING)
            remaining.dischargeAsEmpty(container);
        if (ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING)
            container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
    }

    /**
     * Returns the cardinality of the result of a bitwise AND NOT of the
     * values of the current bitmap with some other bitmap. Avoids
     * allocating an intermediate bitmap to hold the result of the OR.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the cardinality
     * @since 0.4.0
     */
    public int andNotCardinality(final EWAHCompressedBitmap a) {
        final BitCounter counter = new BitCounter();
        andNotToContainer(a, counter);
        return counter.getCount();
    }

    /**
     * reports the number of bits set to true. Running time is proportional
     * to compressed size (as reported by sizeInBytes).
     *
     * @return the number of bits set to true
     */
    public int cardinality() {
        int counter = 0;
        final EWAHIterator i = this.getEWAHIterator();
        while (i.hasNext()) {
            RunningLengthWord localrlw = i.next();
            if (localrlw.getRunningBit()) {
                counter += WORD_IN_BITS * localrlw.getRunningLength();
            }
            for (int j = 0; j < localrlw.getNumberOfLiteralWords(); ++j) {
                counter += Long.bitCount(i.buffer()[i.literalWords() + j]);
            }
        }
        return counter;
    }

    /**
     * Clear any set bits and set size in bits back to 0
     */
    @Override
    public void clear() {
        this.sizeInBits = 0;
        this.actualSizeInWords = 1;
        this.rlw.position = 0;
        // buffer is not fully cleared but any new set operations should
        // overwrite
        // stale data
        this.buffer[0] = 0;
    }

    /*
     * @see java.lang.Object#clone()
     */
    @Override
    public EWAHCompressedBitmap clone() {
        EWAHCompressedBitmap clone = null;
        try {
            clone = (EWAHCompressedBitmap) super.clone();
            clone.buffer = this.buffer.clone();
            clone.actualSizeInWords = this.actualSizeInWords;
            clone.sizeInBits = this.sizeInBits;
            clone.rlw = new RunningLengthWord(clone, this.rlw.position);
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return clone;
    }

    /**
     * Deserialize.
     *
     * @param in the DataInput stream
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public void deserialize(DataInput in) throws IOException {
        this.sizeInBits = in.readInt();
        this.actualSizeInWords = in.readInt();
        if (this.buffer.length < this.actualSizeInWords) {
            this.buffer = new long[this.actualSizeInWords];
        }
        for (int k = 0; k < this.actualSizeInWords; ++k) this.buffer[k] = in.readLong();
        this.rlw = new RunningLengthWord(this, in.readInt());
    }

    /**
     * Check to see whether the two compressed bitmaps contain the same set
     * bits.
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object o) {
        if (o instanceof EWAHCompressedBitmap) {
            try {
                this.xorToContainer((EWAHCompressedBitmap) o, new NonEmptyVirtualStorage());
                return true;
            } catch (NonEmptyVirtualStorage.NonEmptyException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * For experts: You want to add many zeroes or ones faster?
     * 
     * This method does not update sizeInBits.
     *
     * @param v      the boolean value
     * @param number the number (must be greater than 0)
     */
    private void fastaddStreamOfEmptyWords(final boolean v, long number) {
        if ((this.rlw.getRunningBit() != v) && (this.rlw.size() == 0)) {
            this.rlw.setRunningBit(v);
        } else if ((this.rlw.getNumberOfLiteralWords() != 0) || (this.rlw.getRunningBit() != v)) {
            push_back(0);
            this.rlw.position = this.actualSizeInWords - 1;
            if (v)
                this.rlw.setRunningBit(true);
        }
        final long runLen = this.rlw.getRunningLength();
        final long whatWeCanAdd = number < RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT - runLen ? number : RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT - runLen;
        this.rlw.setRunningLength(runLen + whatWeCanAdd);
        number -= whatWeCanAdd;
        while (number >= RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT) {
            push_back(0);
            this.rlw.position = this.actualSizeInWords - 1;
            if (v)
                this.rlw.setRunningBit(true);
            this.rlw.setRunningLength(RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT);
            number -= RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT;
        }
        if (number > 0) {
            push_back(0);
            this.rlw.position = this.actualSizeInWords - 1;
            if (v)
                this.rlw.setRunningBit(true);
            this.rlw.setRunningLength(number);
        }
    }

    /**
     * Gets an EWAHIterator over the data. This is a customized iterator
     * which iterates over run length words. For experts only.
     * 
     * The current bitmap is not modified.
     *
     * @return the EWAHIterator
     */
    public EWAHIterator getEWAHIterator() {
        return new EWAHIterator(this, this.actualSizeInWords);
    }

    /**
     * Gets a ReverseEWAHIterator over the data. This is a customized iterator
     * which iterates over run length words in reverse order. For experts only.
     *
     * The current bitmap is not modified.
     *
     * @return the ReverseEWAHIterator
     */
    private ReverseEWAHIterator getReverseEWAHIterator() {
        return new ReverseEWAHIterator(this, this.actualSizeInWords);
    }

    /**
     * Gets an IteratingRLW to iterate over the data. For experts only.
     * 
     * Note that iterator does not know about the size in bits of the
     * bitmap: the size in bits is effectively rounded up to the nearest
     * multiple of 64. However, if you materialize a bitmap from 
     * an iterator, you can set the desired size in bits using the
     * setSizeInBitsWithinLastWord methods:
     * 
     *  <code>
     *  EWAHCompressedBitmap n = IteratorUtil.materialize(bitmap.getIteratingRLW()));
     *  n.setSizeInBitsWithinLastWord(bitmap.sizeInBits());
     *  </code>

     * 
     * The current bitmap is not modified.
     *
     * @return the IteratingRLW iterator corresponding to this bitmap
     */
    public IteratingRLW getIteratingRLW() {
        return new IteratingBufferedRunningLengthWord(this);
    }

    /**
     * @return a list
     * @deprecated use toList() instead.
     */
    @Deprecated
    public List<Integer> getPositions() {
        return toList();
    }

    /**
     * Gets the locations of the true values as one list. (May use more
     * memory than iterator().)
     * 
     * The current bitmap is not modified.
     * 
     * API change: prior to version 0.8.3, this method was called getPositions.
     *
     * @return the positions in a list
     */
    public List<Integer> toList() {
        final ArrayList<Integer> v = new ArrayList<Integer>();
        final EWAHIterator i = this.getEWAHIterator();
        int pos = 0;
        while (i.hasNext()) {
            RunningLengthWord localrlw = i.next();
            if (localrlw.getRunningBit()) {
                for (int j = 0; j < localrlw.getRunningLength(); ++j) {
                    for (int c = 0; c < WORD_IN_BITS; ++c) v.add(pos++);
                }
            } else {
                pos += WORD_IN_BITS * localrlw.getRunningLength();
            }
            for (int j = 0; j < localrlw.getNumberOfLiteralWords(); ++j) {
                long data = i.buffer()[i.literalWords() + j];
                while (data != 0) {
                    final long T = data & -data;
                    v.add(Long.bitCount(T - 1) + pos);
                    data ^= T;
                }
                pos += WORD_IN_BITS;
            }
        }
        while ((v.size() > 0) && (v.get(v.size() - 1) >= this.sizeInBits)) v.remove(v.size() - 1);
        return v;
    }

    /**
     * Returns a customized hash code (based on Karp-Rabin). Naturally, if
     * the bitmaps are equal, they will hash to the same value.
     * 
     * The current bitmap is not modified.
     */
    @Override
    public int hashCode() {
        int karprabin = 0;
        final int B = 31;
        final EWAHIterator i = this.getEWAHIterator();
        while (i.hasNext()) {
            i.next();
            if (i.rlw.getRunningBit()) {
                karprabin += B * karprabin + (i.rlw.getRunningLength() & ((1l << 32) - 1));
                karprabin += B * karprabin + (i.rlw.getRunningLength() >>> 32);
            }
            for (int k = 0; k < i.rlw.getNumberOfLiteralWords(); ++k) {
                karprabin += B * karprabin + (this.buffer[i.literalWords() + k] & ((1l << 32) - 1));
                karprabin += B * karprabin + (this.buffer[i.literalWords() + k] >>> 32);
            }
        }
        return karprabin;
    }

    /**
     * Return true if the two EWAHCompressedBitmap have both at least one
     * true bit in the same position. Equivalently, you could call "and" and
     * check whether there is a set bit, but intersects will run faster if
     * you don't need the result of the "and" operation.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return whether they intersect
     * @since 0.3.2
     */
    public boolean intersects(final EWAHCompressedBitmap a) {
        NonEmptyVirtualStorage nevs = new NonEmptyVirtualStorage();
        try {
            this.andToContainer(a, nevs);
        } catch (NonEmptyVirtualStorage.NonEmptyException nee) {
            return true;
        }
        return false;
    }

    /**
     * Iterator over the set bits (this is what most people will want to use
     * to browse the content if they want an iterator). The location of the
     * set bits is returned, in increasing order.
     * 
     * The current bitmap is not modified.
     *
     * @return the int iterator
     */
    public IntIterator intIterator() {
        return new IntIteratorImpl(this.getEWAHIterator());
    }

    /**
     * Iterator over the set bits in reverse order.
     *
     * The current bitmap is not modified.
     *
     * @return the int iterator
     */
    public IntIterator reverseIntIterator() {
        return new ReverseIntIterator(this.getReverseEWAHIterator(), this.sizeInBits);
    }

    /**
     * Checks whether this bitmap is empty (has a cardinality of zero).
     * 
     * @return true if no bit is set
     */
    public boolean isEmpty() {
        return getFirstSetBit() < 0;
    }

    /**
     * Iterator over the clear bits. The location of the clear bits is
     * returned, in increasing order.
     * 
     * The current bitmap is not modified.
     *
     * @return the int iterator
     */
    public IntIterator clearIntIterator() {
        return new ClearIntIterator(this.getEWAHIterator(), this.sizeInBits);
    }

    /**
     * Iterator over the chunk of bits.
     *
     * The current bitmap is not modified.
     *
     * @return the chunk iterator
     */
    public ChunkIterator chunkIterator() {
        return new ChunkIteratorImpl(this.getEWAHIterator(), this.sizeInBits);
    }

    /**
     * Iterates over the positions of the true values. This is similar to
     * intIterator(), but it uses Java generics.
     * 
     * The current bitmap is not modified.
     *
     * @return the iterator
     */
    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<Integer>() {

            @Override
            public boolean hasNext() {
                return this.under.hasNext();
            }

            @Override
            public Integer next() {
                return this.under.next();
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("bitsets do not support remove");
            }

            private final IntIterator under = intIterator();
        };
    }

    /**
     * For internal use.
     *
     * @param data   the array of words to be added
     * @param start  the starting point
     * @param number the number of words to add
     */
    private void negative_push_back(final long[] data, final int start, final int number) {
        while (this.actualSizeInWords + number >= this.buffer.length) {
            final long oldBuffer[] = this.buffer;
            if ((this.actualSizeInWords + number) < 32768)
                this.buffer = new long[(this.actualSizeInWords + number) * 2];
            else if ((this.actualSizeInWords + number) * 3 / 2 < this.actualSizeInWords + // overflow
            number)
                this.buffer = new long[Integer.MAX_VALUE];
            else
                this.buffer = new long[(this.actualSizeInWords + number) * 3 / 2];
            System.arraycopy(oldBuffer, 0, this.buffer, 0, oldBuffer.length);
            this.rlw.parent.buffer = this.buffer;
        }
        for (int k = 0; k < number; ++k) this.buffer[this.actualSizeInWords + k] = ~data[start + k];
        this.actualSizeInWords += number;
    }

    /**
     * Negate (bitwise) the current bitmap. To get a negated copy, do
     * EWAHCompressedBitmap x= ((EWAHCompressedBitmap) mybitmap.clone());
     * x.not();
     * 
     * The running time is proportional to the compressed size (as reported
     * by sizeInBytes()).
     * 
     * Because this modifies the bitmap, this method is not thread-safe.
     */
    @Override
    public void not() {
        final EWAHIterator i = this.getEWAHIterator();
        if (!i.hasNext())
            return;
        while (true) {
            final RunningLengthWord rlw1 = i.next();
            rlw1.setRunningBit(!rlw1.getRunningBit());
            for (int j = 0; j < rlw1.getNumberOfLiteralWords(); ++j) {
                i.buffer()[i.literalWords() + j] = ~i.buffer()[i.literalWords() + j];
            }
            if (!i.hasNext()) {
                // must potentially adjust the last
                // literal word
                final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
                if (usedBitsInLast == 0)
                    return;
                if (rlw1.getNumberOfLiteralWords() == 0) {
                    if ((rlw1.getRunningLength() > 0) && (rlw1.getRunningBit())) {
                        if ((rlw1.getRunningLength() == 1) && (rlw1.position > 0)) {
                            // we need to prune ending
                            final EWAHIterator j = this.getEWAHIterator();
                            int newrlwpos = this.rlw.position;
                            while (j.hasNext()) {
                                RunningLengthWord r = j.next();
                                if (r.position < rlw1.position) {
                                    newrlwpos = r.position;
                                } else
                                    break;
                            }
                            this.rlw.position = newrlwpos;
                            this.actualSizeInWords -= 1;
                        } else {
                            rlw1.setRunningLength(rlw1.getRunningLength() - 1);
                        }
                        this.addLiteralWord((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
                    }
                    return;
                }
                i.buffer()[i.literalWords() + rlw1.getNumberOfLiteralWords() - 1] &= ((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
                if (i.buffer()[i.literalWords() + rlw1.getNumberOfLiteralWords() - 1] == 0) {
                    this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
                    this.actualSizeInWords -= 1;
                    this.addEmptyWord(false);
                }
                return;
            }
        }
    }

    /**
     * Returns a new compressed bitmap containing the bitwise OR values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     */
    @Override
    public EWAHCompressedBitmap or(final EWAHCompressedBitmap a) {
        int size = this.actualSizeInWords + a.actualSizeInWords;
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
        orToContainer(a, container);
        return container;
    }

    /**
     * Computes the bitwise or between the current bitmap and the bitmap
     * "a". Stores the result in the container.
     * 
     * The current bitmap is not modified.
     * 
     * The content of the container is overwritten.
     *
     * @param a         the other bitmap (it will not be modified)
     * @param container where we store the result
     * @since 0.4.0
     */
    public void orToContainer(final EWAHCompressedBitmap a, final BitmapStorage container) {
        container.clear();
        final EWAHIterator i = a.getEWAHIterator();
        final EWAHIterator j = getEWAHIterator();
        final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
        final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
        while ((rlwi.size() > 0) && (rlwj.size() > 0)) {
            while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
                final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
                final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
                final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
                if (predator.getRunningBit()) {
                    container.addStreamOfEmptyWords(true, predator.getRunningLength());
                    prey.discardFirstWords(predator.getRunningLength());
                } else {
                    final long index = prey.discharge(container, predator.getRunningLength());
                    container.addStreamOfEmptyWords(false, predator.getRunningLength() - index);
                }
                predator.discardRunningWords();
            }
            final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
            if (nbre_literal > 0) {
                for (int k = 0; k < nbre_literal; ++k) {
                    container.addWord(rlwi.getLiteralWordAt(k) | rlwj.getLiteralWordAt(k));
                }
                rlwi.discardFirstWords(nbre_literal);
                rlwj.discardFirstWords(nbre_literal);
            }
        }
        final boolean i_remains = rlwi.size() > 0;
        final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
        remaining.discharge(container);
        container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
    }

    /**
     * Returns the cardinality of the result of a bitwise OR of the values
     * of the current bitmap with some other bitmap. Avoids
     * allocating an intermediate bitmap to hold the result of the OR.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the cardinality
     * @since 0.4.0
     */
    public int orCardinality(final EWAHCompressedBitmap a) {
        final BitCounter counter = new BitCounter();
        orToContainer(a, counter);
        return counter.getCount();
    }

    /**
     * For internal use.
     *
     * @param data the word to be added
     */
    private void push_back(final long data) {
        if (this.actualSizeInWords == this.buffer.length) {
            final long oldBuffer[] = this.buffer;
            if (oldBuffer.length < 32768)
                this.buffer = new long[oldBuffer.length * 2];
            else if (// overflow
            oldBuffer.length * 3 / 2 < oldBuffer.length)
                this.buffer = new long[Integer.MAX_VALUE];
            else
                this.buffer = new long[oldBuffer.length * 3 / 2];
            System.arraycopy(oldBuffer, 0, this.buffer, 0, oldBuffer.length);
            this.rlw.parent.buffer = this.buffer;
        }
        this.buffer[this.actualSizeInWords++] = data;
    }

    /**
     * For internal use.
     *
     * @param data   the array of words to be added
     * @param start  the starting point
     * @param number the number of words to add
     */
    private void push_back(final long[] data, final int start, final int number) {
        if (this.actualSizeInWords + number >= this.buffer.length) {
            final long oldBuffer[] = this.buffer;
            if (this.actualSizeInWords + number < 32768)
                this.buffer = new long[(this.actualSizeInWords + number) * 2];
            else if (// overflow
            (this.actualSizeInWords + number) * 3 / 2 < this.actualSizeInWords + number)
                this.buffer = new long[Integer.MAX_VALUE];
            else
                this.buffer = new long[(this.actualSizeInWords + number) * 3 / 2];
            System.arraycopy(oldBuffer, 0, this.buffer, 0, oldBuffer.length);
            this.rlw.parent.buffer = this.buffer;
        }
        System.arraycopy(data, start, this.buffer, this.actualSizeInWords, number);
        this.actualSizeInWords += number;
    }

    /*
     * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
     */
    @Override
    public void readExternal(ObjectInput in) throws IOException {
        deserialize(in);
    }

    /**
     * Serialize.
     * 
     * The current bitmap is not modified.
     *
     * @param out the DataOutput stream
     * @throws IOException Signals that an I/O exception has occurred.
     */
    public void serialize(DataOutput out) throws IOException {
        out.writeInt(this.sizeInBits);
        out.writeInt(this.actualSizeInWords);
        for (int k = 0; k < this.actualSizeInWords; ++k) out.writeLong(this.buffer[k]);
        out.writeInt(this.rlw.position);
    }

    /**
     * Report the number of bytes required to serialize this bitmap
     * 
     * The current bitmap is not modified.
     *
     * @return the size in bytes
     */
    public int serializedSizeInBytes() {
        return this.sizeInBytes() + 3 * 4;
    }

    /**
     * Query the value of a single bit. Relying on this method when speed is
     * needed is discouraged. The complexity is linear with the size of the
     * bitmap.
     * 
     * (This implementation is based on zhenjl's Go version of JavaEWAH.)
     * 
     * The current bitmap is not modified.
     *
     * @param i the bit we are interested in
     * @return whether the bit is set to true
     */
    public boolean get(final int i) {
        if ((i < 0) || (i >= this.sizeInBits))
            return false;
        int wordChecked = 0;
        final IteratingRLW j = getIteratingRLW();
        final int wordi = i / WORD_IN_BITS;
        while (wordChecked <= wordi) {
            wordChecked += j.getRunningLength();
            if (wordi < wordChecked) {
                return j.getRunningBit();
            }
            if (wordi < wordChecked + j.getNumberOfLiteralWords()) {
                final long w = j.getLiteralWordAt(wordi - wordChecked);
                return (w & (1l << i)) != 0;
            }
            wordChecked += j.getNumberOfLiteralWords();
            j.next();
        }
        return false;
    }

    /**
     * getFirstSetBit is a light-weight method that returns the
     * location of the set bit (=1) or -1 if there is none.
     * 
     * @return location of the first set bit or -1
     */
    public int getFirstSetBit() {
        int nword = 0;
        for (int pos = 0; pos < this.actualSizeInWords; ++pos) {
            long rl = (this.buffer[pos] >>> 1) & RunningLengthWord.LARGEST_RUNNING_LENGTH_COUNT;
            boolean rb = (this.buffer[pos] & 1) != 0;
            if ((rl > 0) && rb) {
                return nword * WORD_IN_BITS;
            }
            nword += rl;
            long lw = (this.buffer[pos] >>> (1 + RunningLengthWord.RUNNING_LENGTH_BITS));
            if (lw > 0) {
                long word = this.buffer[pos + 1];
                long T = word & -word;
                return nword * WORD_IN_BITS + Long.bitCount(T - 1);
            }
        }
        return -1;
    }

    /**
     * Set the bit at position i to true, the bits must be set in (strictly)
     * increasing order. For example, set(15) and then set(7) will fail. You
     * must do set(7) and then set(15).
     * 
     * Since this modifies the bitmap, this method is not thread-safe.
     *
     * @param i the index
     * @return true if the value was set (always true when i greater or
     * equal to sizeInBits()).
     * @throws IndexOutOfBoundsException if i is negative or greater than Integer.MAX_VALUE -
     *                                   64
     */
    public boolean set(final int i) {
        if ((i > Integer.MAX_VALUE - WORD_IN_BITS) || (i < 0))
            throw new IndexOutOfBoundsException("Set values should be between 0 and " + (Integer.MAX_VALUE - WORD_IN_BITS));
        if (i < this.sizeInBits)
            return false;
        // distance in words:
        final int dist = (i + WORD_IN_BITS) / WORD_IN_BITS - (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS;
        this.sizeInBits = i + 1;
        if (dist > 0) {
            // easy
            if (dist > 1)
                fastaddStreamOfEmptyWords(false, dist - 1);
            addLiteralWord(1l << (i % WORD_IN_BITS));
            return true;
        }
        if (this.rlw.getNumberOfLiteralWords() == 0) {
            this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
            addLiteralWord(1l << (i % WORD_IN_BITS));
            return true;
        }
        this.buffer[this.actualSizeInWords - 1] |= 1l << (i % WORD_IN_BITS);
        if (this.buffer[this.actualSizeInWords - 1] == ~0l) {
            this.buffer[this.actualSizeInWords - 1] = 0;
            --this.actualSizeInWords;
            this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
            // next we add one clean word
            addEmptyWord(true);
        }
        return true;
    }

    @Override
    public void setSizeInBitsWithinLastWord(final int size) {
        if ((size + WORD_IN_BITS - 1) / WORD_IN_BITS != (this.sizeInBits + WORD_IN_BITS - 1) / WORD_IN_BITS)
            throw new RuntimeException("You can only reduce the size of the bitmap within the scope of the last word. To extend the bitmap, please call setSizeInBits(int,boolean).");
        this.sizeInBits = size;
        final int usedBitsInLast = this.sizeInBits % WORD_IN_BITS;
        if (usedBitsInLast == 0)
            return;
        if (this.rlw.getNumberOfLiteralWords() == 0) {
            if ((this.rlw.getRunningLength() > 0) && (this.rlw.getRunningBit())) {
                if ((this.rlw.getRunningLength() == 1) && (this.rlw.position > 0)) {
                    // we need to prune ending
                    final EWAHIterator j = this.getEWAHIterator();
                    int newrlwpos = this.rlw.position;
                    while (j.hasNext()) {
                        RunningLengthWord r = j.next();
                        if (r.position < this.rlw.position) {
                            newrlwpos = r.position;
                        } else
                            break;
                    }
                    this.rlw.position = newrlwpos;
                    this.actualSizeInWords -= 1;
                } else {
                    this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
                }
                this.addLiteralWord((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
            }
            return;
        }
        this.buffer[this.actualSizeInWords - 1] &= ((~0l) >>> (WORD_IN_BITS - usedBitsInLast));
        if (this.buffer[this.actualSizeInWords - 1] == 0) {
            this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
            this.actualSizeInWords -= 1;
            this.addEmptyWord(false);
        }
    }

    /**
     * Change the reported size in bits of the *uncompressed* bitmap
     * represented by this compressed bitmap. It may change the underlying
     * compressed bitmap. It is not possible to reduce the sizeInBits, but
     * it can be extended. The new bits are set to false or true depending
     * on the value of defaultValue.
     * 
     * This method is not thread-safe.
     *
     * @param size         the size in bits
     * @param defaultValue the default boolean value
     * @return true if the update was possible
     */
    public boolean setSizeInBits(final int size, final boolean defaultValue) {
        if (size <= this.sizeInBits)
            return false;
        if (!defaultValue) {
            extendEmptyBits(this, this.sizeInBits, size);
        } else {
            if ((this.sizeInBits % WORD_IN_BITS) != 0) {
                if (this.rlw.getNumberOfLiteralWords() == 0) {
                    this.rlw.setRunningLength(this.rlw.getRunningLength() - 1);
                    addLiteralWord(0);
                }
                final int maskWidth;
                final int maskShift = this.sizeInBits % WORD_IN_BITS;
                if (this.sizeInBits + WORD_IN_BITS - this.sizeInBits % WORD_IN_BITS < size) {
                    maskWidth = WORD_IN_BITS - this.sizeInBits % WORD_IN_BITS;
                } else {
                    maskWidth = size - this.sizeInBits;
                }
                this.buffer[this.actualSizeInWords - 1] |= ((~0l) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
                if (this.buffer[this.actualSizeInWords - 1] == ~0l) {
                    this.buffer[this.actualSizeInWords - 1] = 0;
                    --this.actualSizeInWords;
                    this.rlw.setNumberOfLiteralWords(this.rlw.getNumberOfLiteralWords() - 1);
                    addEmptyWord(true);
                }
                this.sizeInBits += maskWidth;
            }
            this.addStreamOfEmptyWords(defaultValue, (size / WORD_IN_BITS) - this.sizeInBits / WORD_IN_BITS);
            if (this.sizeInBits < size) {
                if (this.rlw.getNumberOfLiteralWords() == 0) {
                    addLiteralWord(0);
                }
                final int maskWidth = size - this.sizeInBits;
                final int maskShift = this.sizeInBits % WORD_IN_BITS;
                this.buffer[this.actualSizeInWords - 1] |= ((~0l) >>> (WORD_IN_BITS - maskWidth)) << maskShift;
            }
        }
        this.sizeInBits = size;
        return true;
    }

    /**
     * Returns the size in bits of the *uncompressed* bitmap represented by
     * this compressed bitmap. Initially, the sizeInBits is zero. It is
     * extended automatically when you set bits to true.
     * 
     * The current bitmap is not modified.
     *
     * @return the size in bits
     */
    @Override
    public int sizeInBits() {
        return this.sizeInBits;
    }

    /**
     * Report the *compressed* size of the bitmap (equivalent to memory
     * usage, after accounting for some overhead).
     *
     * @return the size in bytes
     */
    @Override
    public int sizeInBytes() {
        return this.actualSizeInWords * (WORD_IN_BITS / 8);
    }

    /**
     * Compute a Boolean threshold function: bits are true where at least t
     * bitmaps have a true bit.
     *
     * @param t       the threshold
     * @param bitmaps input data
     * @return the aggregated bitmap
     * @since 0.8.1
     */
    public static EWAHCompressedBitmap threshold(final int t, final EWAHCompressedBitmap... bitmaps) {
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap();
        thresholdWithContainer(container, t, bitmaps);
        return container;
    }

    /**
     * Compute a Boolean threshold function: bits are true where at least T
     * bitmaps have a true bit.
     * 
     * The content of the container is overwritten.
     *
     * @param t         the threshold
     * @param bitmaps   input data
     * @param container where we write the aggregated bitmap
     * @since 0.8.1
     */
    public static void thresholdWithContainer(final BitmapStorage container, final int t, final EWAHCompressedBitmap... bitmaps) {
        (new RunningBitmapMerge()).symmetric(new ThresholdFuncBitmap(t), container, bitmaps);
    }

    /**
     * Populate an array of (sorted integers) corresponding to the location
     * of the set bits.
     *
     * @return the array containing the location of the set bits
     */
    public int[] toArray() {
        int[] ans = new int[this.cardinality()];
        int inAnsPos = 0;
        int pos = 0;
        final EWAHIterator i = this.getEWAHIterator();
        while (i.hasNext()) {
            RunningLengthWord localRlw = i.next();
            if (localRlw.getRunningBit()) {
                for (int j = 0; j < localRlw.getRunningLength(); ++j) {
                    for (int c = 0; c < WORD_IN_BITS; ++c) {
                        ans[inAnsPos++] = pos++;
                    }
                }
            } else {
                pos += WORD_IN_BITS * localRlw.getRunningLength();
            }
            for (int j = 0; j < localRlw.getNumberOfLiteralWords(); ++j) {
                long data = i.buffer()[i.literalWords() + j];
                while (data != 0) {
                    final long T = data & -data;
                    ans[inAnsPos++] = Long.bitCount(T - 1) + pos;
                    data ^= T;
                }
                pos += WORD_IN_BITS;
            }
        }
        return ans;
    }

    /**
     * A more detailed string describing the bitmap (useful for debugging).
     *
     * @return the string
     */
    public String toDebugString() {
        StringBuilder ans = new StringBuilder();
        ans.append(" EWAHCompressedBitmap, size in bits = ");
        ans.append(this.sizeInBits).append(" size in words = ");
        ans.append(this.actualSizeInWords).append("\n");
        final EWAHIterator i = this.getEWAHIterator();
        while (i.hasNext()) {
            RunningLengthWord localrlw = i.next();
            if (localrlw.getRunningBit()) {
                ans.append(localrlw.getRunningLength()).append(" 1x11\n");
            } else {
                ans.append(localrlw.getRunningLength()).append(" 0x00\n");
            }
            ans.append(localrlw.getNumberOfLiteralWords()).append(" dirties\n");
            for (int j = 0; j < localrlw.getNumberOfLiteralWords(); ++j) {
                long data = i.buffer()[i.literalWords() + j];
                ans.append("\t").append(data).append("\n");
            }
        }
        return ans.toString();
    }

    /**
     * A string describing the bitmap.
     *
     * @return the string
     */
    @Override
    public String toString() {
        StringBuilder answer = new StringBuilder();
        IntIterator i = this.intIterator();
        answer.append("{");
        if (i.hasNext())
            answer.append(i.next());
        while (i.hasNext()) {
            answer.append(",");
            answer.append(i.next());
        }
        answer.append("}");
        return answer.toString();
    }

    /**
     * Swap the content of the bitmap with another.
     *
     * @param other bitmap to swap with
     */
    public void swap(final EWAHCompressedBitmap other) {
        long[] tmp = this.buffer;
        this.buffer = other.buffer;
        other.buffer = tmp;
        int tmp2 = this.rlw.position;
        this.rlw.position = other.rlw.position;
        other.rlw.position = tmp2;
        int tmp3 = this.actualSizeInWords;
        this.actualSizeInWords = other.actualSizeInWords;
        other.actualSizeInWords = tmp3;
        int tmp4 = this.sizeInBits;
        this.sizeInBits = other.sizeInBits;
        other.sizeInBits = tmp4;
    }

    /**
     * Reduce the internal buffer to its minimal allowable size (given by
     * this.actualSizeInWords). This can free memory.
     */
    public void trim() {
        this.buffer = Arrays.copyOf(this.buffer, this.actualSizeInWords);
    }

    /*
     * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
     */
    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        serialize(out);
    }

    /**
     * Returns a new compressed bitmap containing the bitwise XOR values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     */
    @Override
    public EWAHCompressedBitmap xor(final EWAHCompressedBitmap a) {
        int size = this.actualSizeInWords + a.actualSizeInWords;
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
        xorToContainer(a, container);
        return container;
    }

    /**
     * Computes a new compressed bitmap containing the bitwise XOR values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * The current bitmap is not modified.
     * 
     * The content of the container is overwritten.
     *
     * @param a         the other bitmap (it will not be modified)
     * @param container where we store the result
     * @since 0.4.0
     */
    public void xorToContainer(final EWAHCompressedBitmap a, final BitmapStorage container) {
        container.clear();
        final EWAHIterator i = a.getEWAHIterator();
        final EWAHIterator j = getEWAHIterator();
        final IteratingBufferedRunningLengthWord rlwi = new IteratingBufferedRunningLengthWord(i);
        final IteratingBufferedRunningLengthWord rlwj = new IteratingBufferedRunningLengthWord(j);
        while ((rlwi.size() > 0) && (rlwj.size() > 0)) {
            while ((rlwi.getRunningLength() > 0) || (rlwj.getRunningLength() > 0)) {
                final boolean i_is_prey = rlwi.getRunningLength() < rlwj.getRunningLength();
                final IteratingBufferedRunningLengthWord prey = i_is_prey ? rlwi : rlwj;
                final IteratingBufferedRunningLengthWord predator = i_is_prey ? rlwj : rlwi;
                final long index = (!predator.getRunningBit()) ? prey.discharge(container, predator.getRunningLength()) : prey.dischargeNegated(container, predator.getRunningLength());
                container.addStreamOfEmptyWords(predator.getRunningBit(), predator.getRunningLength() - index);
                predator.discardRunningWords();
            }
            final int nbre_literal = Math.min(rlwi.getNumberOfLiteralWords(), rlwj.getNumberOfLiteralWords());
            if (nbre_literal > 0) {
                for (int k = 0; k < nbre_literal; ++k) container.addWord(rlwi.getLiteralWordAt(k) ^ rlwj.getLiteralWordAt(k));
                rlwi.discardFirstWords(nbre_literal);
                rlwj.discardFirstWords(nbre_literal);
            }
        }
        final boolean i_remains = rlwi.size() > 0;
        final IteratingBufferedRunningLengthWord remaining = i_remains ? rlwi : rlwj;
        remaining.discharge(container);
        container.setSizeInBitsWithinLastWord(Math.max(sizeInBits(), a.sizeInBits()));
    }

    /**
     * Returns the cardinality of the result of a bitwise XOR of the values
     * of the current bitmap with some other bitmap. Avoids
     * allocating an intermediate bitmap to hold the result of the OR.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the cardinality
     * @since 0.4.0
     */
    public int xorCardinality(final EWAHCompressedBitmap a) {
        final BitCounter counter = new BitCounter();
        xorToContainer(a, counter);
        return counter.getCount();
    }

    /**
     * Returns a new compressed bitmap containing the composition of
     * the current bitmap with some other bitmap.
     *
     * The composition A.compose(B) is defined as follows: we retain
     * the ith set bit of A only if the ith bit of B is set. For example, 
     * if you have the following bitmap A = { 0, 1, 0, 1, 1, 0 } and want
     * to keep only the second and third ones, you can call A.compose(B) 
     * with B = { 0, 1, 1 } and you will get C = { 0, 0, 0, 1, 1, 0 }.
     *
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     *
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     */
    @Override
    public EWAHCompressedBitmap compose(EWAHCompressedBitmap a) {
        int size = this.actualSizeInWords;
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
        composeToContainer(a, container);
        return container;
    }

    /**
     * Computes a new compressed bitmap containing the composition of
     * the current bitmap with some other bitmap.
     * 
     * The composition A.compose(B) is defined as follows: we retain
     * the ith set bit of A only if the ith bit of B is set. For example, 
     * if you have the following bitmap A = { 0, 1, 0, 1, 1, 0 } and want
     * to keep only the second and third ones, you can call A.compose(B) 
     * with B = { 0, 1, 1 } and you will get C = { 0, 0, 0, 1, 1, 0 }.
     *
     * The current bitmap is not modified.
     *
     * The content of the container is overwritten.
     *
     * @param a         the other bitmap (it will not be modified)
     * @param container where we store the result
     */
    public void composeToContainer(final EWAHCompressedBitmap a, final EWAHCompressedBitmap container) {
        container.clear();
        final ChunkIterator iterator = chunkIterator();
        final ChunkIterator aIterator = a.chunkIterator();
        int index = 0;
        while (iterator.hasNext() && aIterator.hasNext()) {
            if (!iterator.nextBit()) {
                int length = iterator.nextLength();
                index += length;
                container.setSizeInBits(index, false);
                iterator.move(length);
            } else {
                int length = Math.min(iterator.nextLength(), aIterator.nextLength());
                index += length;
                container.setSizeInBits(index, aIterator.nextBit());
                iterator.move(length);
                aIterator.move(length);
            }
        }
        container.setSizeInBits(sizeInBits, false);
    }

    /**
     * For internal use. Computes the bitwise and of the provided bitmaps
     * and stores the result in the container.
     * 
     * The content of the container is overwritten.
     *
     * @param container where the result is stored
     * @param bitmaps   bitmaps to AND
     * @since 0.4.3
     */
    public static void andWithContainer(final BitmapStorage container, final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length == 1)
            throw new IllegalArgumentException("Need at least one bitmap");
        if (bitmaps.length == 2) {
            bitmaps[0].andToContainer(bitmaps[1], container);
            return;
        }
        int initialSize = calculateInitialSize(bitmaps);
        EWAHCompressedBitmap answer = new EWAHCompressedBitmap(initialSize);
        EWAHCompressedBitmap tmp = new EWAHCompressedBitmap(initialSize);
        bitmaps[0].andToContainer(bitmaps[1], answer);
        for (int k = 2; k < bitmaps.length - 1; ++k) {
            answer.andToContainer(bitmaps[k], tmp);
            EWAHCompressedBitmap tmp2 = answer;
            answer = tmp;
            tmp = tmp2;
            tmp.clear();
        }
        answer.andToContainer(bitmaps[bitmaps.length - 1], container);
    }

    private static int calculateInitialSize(final EWAHCompressedBitmap... bitmaps) {
        int initialSize = DEFAULT_BUFFER_SIZE;
        for (EWAHCompressedBitmap bitmap : bitmaps) initialSize = Math.max(bitmap.actualSizeInWords, initialSize);
        return initialSize;
    }

    /**
     * Returns a new compressed bitmap containing the bitwise AND values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     * @since 0.4.3
     */
    public static EWAHCompressedBitmap and(final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length == 1)
            return bitmaps[0];
        if (bitmaps.length == 2)
            return bitmaps[0].and(bitmaps[1]);
        int initialSize = calculateInitialSize(bitmaps);
        EWAHCompressedBitmap answer = new EWAHCompressedBitmap(initialSize);
        EWAHCompressedBitmap tmp = new EWAHCompressedBitmap(initialSize);
        bitmaps[0].andToContainer(bitmaps[1], answer);
        for (int k = 2; k < bitmaps.length; ++k) {
            answer.andToContainer(bitmaps[k], tmp);
            tmp.swap(answer);
            tmp.clear();
        }
        return answer;
    }

    /**
     * Returns the cardinality of the result of a bitwise AND of the values
     * of the current bitmap with some other bitmap. Avoids
     * allocating an intermediate bitmap to hold the result of the OR.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the cardinality
     * @since 0.4.0
     */
    public static int andCardinality(final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length == 1)
            return bitmaps[0].cardinality();
        final BitCounter counter = new BitCounter();
        andWithContainer(counter, bitmaps);
        return counter.getCount();
    }

    /**
     * Return a bitmap with the bit set to true at the given positions. The
     * positions should be given in sorted order.
     * 
     * (This is a convenience method.)
     *
     * @param setBits list of set bit positions
     * @return the bitmap
     * @since 0.4.5
     */
    public static EWAHCompressedBitmap bitmapOf(int... setBits) {
        EWAHCompressedBitmap a = new EWAHCompressedBitmap();
        for (int k : setBits) a.set(k);
        return a;
    }

    /**
     * For internal use. This simply adds a stream of words made of zeroes
     * so that we pad to the desired size.
     *
     * @param storage     bitmap to extend
     * @param currentSize current size (in bits)
     * @param newSize     new desired size (in bits)
     * @since 0.4.3
     */
    private static void extendEmptyBits(final BitmapStorage storage, final int currentSize, final int newSize) {
        final int currentLeftover = currentSize % WORD_IN_BITS;
        final int finalLeftover = newSize % WORD_IN_BITS;
        storage.addStreamOfEmptyWords(false, (newSize / WORD_IN_BITS) - currentSize / WORD_IN_BITS + (finalLeftover != 0 ? 1 : 0) + (currentLeftover != 0 ? -1 : 0));
    }

    /**
     * Uses an adaptive technique to compute the logical OR. Mostly for
     * internal use.
     * 
     * The content of the container is overwritten.
     *
     * @param container where the aggregate is written.
     * @param bitmaps   to be aggregated
     */
    public static void orWithContainer(final BitmapStorage container, final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length < 2)
            throw new IllegalArgumentException("You should provide at least two bitmaps, provided " + bitmaps.length);
        long size = 0L;
        long sinBits = 0L;
        for (EWAHCompressedBitmap b : bitmaps) {
            size += b.sizeInBytes();
            if (sinBits < b.sizeInBits())
                sinBits = b.sizeInBits();
        }
        if (size * 8 > sinBits) {
            FastAggregation.bufferedorWithContainer(container, 65536, bitmaps);
        } else {
            FastAggregation.orToContainer(container, bitmaps);
        }
    }

    /**
     * Uses an adaptive technique to compute the logical XOR. Mostly for
     * internal use.
     * 
     * The content of the container is overwritten.
     *
     * @param container where the aggregate is written.
     * @param bitmaps   to be aggregated
     */
    public static void xorWithContainer(final BitmapStorage container, final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length < 2)
            throw new IllegalArgumentException("You should provide at least two bitmaps, provided " + bitmaps.length);
        long size = 0L;
        long sizeInBits = 0L;
        for (EWAHCompressedBitmap b : bitmaps) {
            size += b.sizeInBytes();
            if (sizeInBits < b.sizeInBits())
                sizeInBits = b.sizeInBits();
        }
        if (size * 8 > sizeInBits) {
            FastAggregation.bufferedxorWithContainer(container, 65536, bitmaps);
        } else {
            FastAggregation.xorToContainer(container, bitmaps);
        }
    }

    /**
     * Returns a new compressed bitmap containing the bitwise OR values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     */
    public static EWAHCompressedBitmap or(final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length == 1)
            return bitmaps[0];
        int largestSize = calculateInitialSize(bitmaps);
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap((int) (largestSize * 1.5));
        orWithContainer(container, bitmaps);
        return container;
    }

    /**
     * Returns a new compressed bitmap containing the bitwise XOR values of
     * the current bitmap with some other bitmap.
     * 
     * The running time is proportional to the sum of the compressed sizes
     * (as reported by sizeInBytes()).
     * 
     * If you are not planning on adding to the resulting bitmap, you may
     * call the trim() method to reduce memory usage.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the EWAH compressed bitmap
     */
    public static EWAHCompressedBitmap xor(final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length == 1)
            return bitmaps[0];
        int largestSize = calculateInitialSize(bitmaps);
        int size = (int) (largestSize * 1.5);
        final EWAHCompressedBitmap container = new EWAHCompressedBitmap(size);
        xorWithContainer(container, bitmaps);
        return container;
    }

    /**
     * Returns the cardinality of the result of a bitwise OR of the values
     * of the current bitmap with some other bitmap. Avoids
     * allocating an intermediate bitmap to hold the result of the OR.
     * 
     * The current bitmap is not modified.
     *
     * @param a the other bitmap (it will not be modified)
     * @return the cardinality
     * @since 0.4.0
     */
    public static int orCardinality(final EWAHCompressedBitmap... bitmaps) {
        if (bitmaps.length == 1)
            return bitmaps[0].cardinality();
        final BitCounter counter = new BitCounter();
        orWithContainer(counter, bitmaps);
        return counter.getCount();
    }

    /**
     * The actual size in words.
     */
    private int actualSizeInWords = 1;

    /**
     * The buffer (array of 64-bit words)
     */
    protected long buffer[] = null;

    /**
     * The current (last) running length word.
     */
    private RunningLengthWord rlw = null;

    /**
     * sizeInBits: number of bits in the (uncompressed) bitmap.
     */
    protected int sizeInBits = 0;

    /**
     * The Constant DEFAULT_BUFFER_SIZE: default memory allocation when the
     * object is constructed.
     */
    public static final int DEFAULT_BUFFER_SIZE = 4;

    /**
     * whether we adjust after some aggregation by adding in zeroes *
     */
    public static final boolean ADJUST_CONTAINER_SIZE_WHEN_AGGREGATING = true;

    /**
     * The Constant WORD_IN_BITS represents the number of bits in a long.
     */
    public static final int WORD_IN_BITS = 64;

    static final long serialVersionUID = 1L;
}
