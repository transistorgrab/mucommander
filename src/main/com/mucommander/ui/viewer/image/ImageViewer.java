/*
 * This file is part of muCommander, http://www.mucommander.com
 * Copyright (C) 2002-2012 Maxence Bernard
 *
 * muCommander is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * muCommander is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.mucommander.ui.viewer.image;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import javax.swing.*;

import com.mucommander.commons.file.AbstractFile;
import com.mucommander.commons.runtime.OsFamily;
import com.mucommander.text.Translator;
import com.mucommander.ui.dialog.InformationDialog;
import com.mucommander.ui.helper.MenuToolkit;
import com.mucommander.ui.helper.MnemonicHelper;
import com.mucommander.ui.theme.ColorChangedEvent;
import com.mucommander.ui.theme.FontChangedEvent;
import com.mucommander.ui.theme.Theme;
import com.mucommander.ui.theme.ThemeListener;
import com.mucommander.ui.theme.ThemeManager;
import com.mucommander.ui.viewer.FileFrame;
import com.mucommander.ui.viewer.FileViewer;
import net.sf.image4j.codec.ico.ICODecoder;
import org.apache.sanselan.ImageReadException;
import org.apache.sanselan.formats.pnm.PNMImageParser;
import org.apache.sanselan.formats.psd.PsdImageParser;
import org.apache.sanselan.formats.tiff.TiffImageParser;

//import org.apache.commons.imaging.Imaging;



/**
 * A simple image viewer, capable of displaying <code>PNG</code>, <code>GIF</code> and <code>JPEG</code> images. 
 *
 * @author Maxence Bernard, Arik Hadas
 */
class ImageViewer extends FileViewer implements ActionListener {

    private static final Cursor CURSOR_WAIT = new Cursor(Cursor.WAIT_CURSOR);
    private static final Cursor CURSOR_DEFAULT = Cursor.getDefaultCursor();
    private static final Cursor CURSOR_CROSS = new Cursor(Cursor.CROSSHAIR_CURSOR);

    private BufferedImage image;
    private BufferedImage scaledImage;
    private double zoomFactor;
	
    /** Menu bar */
    // Menus //
    private JMenu controlsMenu;
    // Items //
    private JMenuItem prevImageItem;
    private JMenuItem nextImageItem;
    private JMenuItem zoomInItem;
    private JMenuItem zoomOutItem;

    private ImageViewerImpl imageViewerImpl;
    private List<AbstractFile> filesInDirectory;
    private int indexInDirectory = -1;

    private StatusBar statusBar;

    private boolean waitCursorMode = false;

    /**
     * Unknown swing issue = MouseMovement events doesn't received on windows show.
     * To fix it we make windows resize and undo it after start.
     */
    private boolean mouseMovementIssueFixed = false;

    static {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        registry.registerServiceProvider(new com.realityinteractive.imageio.tga.TGAImageReaderSpi());
    }


    public ImageViewer() {
    	imageViewerImpl = new ImageViewerImpl();
    	
    	setComponentToPresent(imageViewerImpl);
    	
    	// Create Go menu
    	MnemonicHelper menuMnemonicHelper = new MnemonicHelper();
    	controlsMenu = MenuToolkit.addMenu(Translator.get("image_viewer.controls_menu"), menuMnemonicHelper, null);
    	
        nextImageItem = MenuToolkit.addMenuItem(controlsMenu, Translator.get("image_viewer.next_image"), menuMnemonicHelper, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), this);
        prevImageItem = MenuToolkit.addMenuItem(controlsMenu, Translator.get("image_viewer.previous_image"), menuMnemonicHelper, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), this);
        controlsMenu.add(new JSeparator());
        if (OsFamily.getCurrent() != OsFamily.MAC_OS_X) {
            zoomInItem = MenuToolkit.addMenuItem(controlsMenu, Translator.get("image_viewer.zoom_in"), menuMnemonicHelper, KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0), this);
            zoomOutItem = MenuToolkit.addMenuItem(controlsMenu, Translator.get("image_viewer.zoom_out"), menuMnemonicHelper, KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0), this);
        } else {
            zoomInItem = MenuToolkit.addMenuItem(controlsMenu, Translator.get("image_viewer.zoom_in"), menuMnemonicHelper, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), this);
            zoomOutItem = MenuToolkit.addMenuItem(controlsMenu, Translator.get("image_viewer.zoom_out"), menuMnemonicHelper, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), this);
        }
    }
    
    @Override
    public JMenuBar getMenuBar() {
    	JMenuBar menuBar = super.getMenuBar();
    	
        menuBar.add(controlsMenu);
        setMainKeyListener(imageViewerImpl, menuBar);
    	
    	return menuBar;
    }

    @Override
    protected StatusBar getStatusBar() {
        statusBar = new StatusBar();
        return statusBar;
    }

    @Override
    protected void saveStateOnClose() {

    }

    @Override
    protected void restoreStateOnStartup() {

    }

    private synchronized void loadImage(AbstractFile file) throws IOException, ImageReadException {
        setFrameCursor(CURSOR_WAIT);

        statusBar.setFileSize(file.getSize());
        statusBar.setDateTime(file.getDate());
        int imageWidth, imageHeight;
        this.scaledImage = null;
        final String ext = file.getExtension().toLowerCase();
        if ("scr".equals(ext) && file.getSize() == ZxSpectrumScrImage.SCR_IMAGE_FILE_SIZE) {
            this.image = ZxSpectrumScrImage.load(file.getInputStream());
            statusBar.setImageBpp(4);
        } else if ("psd".equals(ext)) {
            this.image = new PsdImageParser().getBufferedImage(loadFile(file), null);
        } else if ("tif".equals(ext) || "tiff".equals(ext)) {
            this.image = new TiffImageParser().getBufferedImage(loadFile(file), null);
        } else if ("ico".equals(ext)) {
            this.image = ICODecoder.read(file.getInputStream()).get(0);
            //this.image = (BufferedImage) (new IcoImageParser().getAllBufferedImages(loadFile(file)).get(0));
        } else if ("pnm".equals(ext) || "pbm".equals(ext) || "pgm".equals(ext) || "ppm".equals(ext)) {
            // TODO pBm raw format reading error
            this.image = (BufferedImage) (new PNMImageParser().getAllBufferedImages(loadFile(file)).get(0));
        } else {
            this.image = ImageIO.read(file.getInputStream());
            statusBar.setImageBpp(image.getColorModel().getPixelSize());
        }
        imageWidth = image.getWidth();
        imageHeight = image.getHeight();

        statusBar.setImageSize(imageWidth, imageHeight);

        this.zoomFactor = 1.0;
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();

        double zoomFactorX = 1.0 * screen.width / imageWidth;
        double zoomFactorY = 1.0 * screen.height / imageHeight;
        zoomFactor = Math.min(zoomFactorX, zoomFactorY);
        if (zoomFactor > 1.0) {
            zoomFactor = 1.0;
        }

        zoom(zoomFactor);
        fixMouseMovementEventsIssue();

        checkNextPrev();
        setFrameCursor(CURSOR_DEFAULT);
    }


    private static byte[] loadFile(AbstractFile file) throws IOException {
        InputStream is = file.getInputStream();
        byte[] data = new byte[(int)file.getSize()];
        int readTotal = 0;
        try {
            while (readTotal < data.length) {
                int bytesRead = is.read(data, readTotal, data.length - readTotal);
                if (bytesRead < 0) {
                    break;
                }
                readTotal += bytesRead;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (is != null) {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return data;
    }


    private void setFrameCursor(Cursor cursor) {
        if (cursor == CURSOR_WAIT) {
            waitCursorMode = true;
        } else if (cursor == CURSOR_DEFAULT) {
            waitCursorMode = false;
        } else if (cursor == CURSOR_CROSS && waitCursorMode) {
            return;
        }
        if (getFrame().getCursor() != cursor) {
            getFrame().setCursor(cursor);
        }
    }

	

    private synchronized void zoom(double factor) {
        setFrameCursor(CURSOR_WAIT);

        final int srcWidth = image.getWidth(null);
        final int srcHeight = image.getHeight(null);
        final int scaledWidth = (int)(srcWidth*factor);
        final int scaledHeight = (int)(srcHeight*factor);
        //this.scaledImage = image.getScaledInstance(newWidth, newHeight, Image.SCALE_DEFAULT);

        if (factor != 1.0) {
            this.scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_ARGB);
            AffineTransform at = new AffineTransform();
            at.scale(factor, factor);
            AffineTransformOp scaleOp = new AffineTransformOp(at, AffineTransformOp.TYPE_BILINEAR);
            this.scaledImage = scaleOp.filter(this.image, this.scaledImage);
        } else {
            this.scaledImage = image;
        }

        statusBar.setZoom(factor);
        checkZoom();
        setFrameCursor(CURSOR_DEFAULT);
    }


    private void fixMouseMovementEventsIssue() {
        if (mouseMovementIssueFixed) {
            return;
        }
        mouseMovementIssueFixed = true;
        Runnable task = new Runnable() {
            public void run() {
                try {
                    int w = getFrame().getWidth();
                    int h = getFrame().getHeight();
                    getFrame().setSize(w, h-1);
                    getFrame().setSize(w, h);
                } catch (Exception e) {
                }
            }
        };
        Executors.newSingleThreadScheduledExecutor().schedule(task, 1000, TimeUnit.MILLISECONDS);
    }

    private void updateFrame() {
    	FileFrame frame = getFrame();

        // Revalidate, pack and repaint should be called in this order
        frame.setTitle(this.getTitle());
        imageViewerImpl.revalidate();
        //frame.pack();
        frame.getContentPane().repaint();

    }

    private void checkZoom() {
        Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
		
        zoomInItem.setEnabled(zoomFactor<1.0 || (2*zoomFactor*image.getWidth(null) < d.width
                                                 && 2*zoomFactor*image.getHeight(null) < d.height));

        zoomOutItem.setEnabled(zoomFactor > 1.0 || (zoomFactor / 2 * image.getWidth(null) > 160
                && zoomFactor / 2 * image.getHeight(null) > 120));
    }

    private void checkNextPrev() {
        prevImageItem.setEnabled(getPrevFileIndex() >= 0);
        nextImageItem.setEnabled(getNextFileIndex() >= 0);
    }

    ///////////////////////////////
    // FileViewer implementation //
    ///////////////////////////////

    @Override
    public void show(AbstractFile file) throws IOException {
        if (filesInDirectory == null) {
            filesInDirectory = new ArrayList<>();
            AbstractFile ls[] = file.getParent().ls();
            ImageFactory imageFactory = new ImageFactory();
            for (AbstractFile f : ls) {
                if (imageFactory.canViewFile(f)) {
                    filesInDirectory.add(f);
                }
            }
            for (int i = 0 ; i < filesInDirectory.size(); i++) {
                AbstractFile f = filesInDirectory.get(i);
                if (f.equals(file)) {
                    indexInDirectory = i;
                    break;
                }
            }
            statusBar.setFileNumber(indexInDirectory+1, filesInDirectory.size());
        }
        try {
            loadImage(file);
        } catch (ImageReadException e) {
            e.printStackTrace();
            throw new IOException("Image parsing error", e);
        }
    }

    ///////////////////////////////////
    // ActionListener implementation //
    ///////////////////////////////////

    @Override
    public String getTitle() {
        return new StringBuilder(filesInDirectory.get(indexInDirectory).toString()).
                append(" - ").append(image.getWidth(null)).append("x").append(image.getHeight(null)).append(" - ").
                append((int) (zoomFactor * 100)).append("%").
                toString();
        //return file.getAbsolutePath()+" - "+image.getWidth(null)+"x"+image.getHeight(null)+" - "+((int)(zoomFactor*100))+"%";
    }

    public void actionPerformed(ActionEvent e) {
        Object source = e.getSource();

        if (source == zoomInItem && zoomInItem.isEnabled()) {
            zoomFactor *= 2;
            zoom(zoomFactor);
            updateFrame();
        } else if(source == zoomOutItem && zoomOutItem.isEnabled()) {
            zoomFactor /= 2;
            zoom(zoomFactor);
            updateFrame();
        } else if (source == nextImageItem && nextImageItem.isEnabled()) {
            gotoNextFile();
        } else if (source == prevImageItem && prevImageItem.isEnabled()) {
            gotoPrevFile();
        } else {
        	super.actionPerformed(e);
        }
    }

    private int getNextFileIndex() {
        return indexInDirectory < filesInDirectory.size() - 1 ? indexInDirectory + 1 : -1;
    }

    private void gotoNextFile() {
        int index = getNextFileIndex();
        if (index >= 0) {
            indexInDirectory = index;
            gotoFile();
        }
    }

    private int getPrevFileIndex() {
        return indexInDirectory > 0 ? indexInDirectory - 1 : -1;
    }

    private void gotoPrevFile() {
        int index = getPrevFileIndex();
        if (index >= 0) {
            indexInDirectory = index;
            gotoFile();
        }
    }

    private void gotoFile() {
        try {
            show(filesInDirectory.get(indexInDirectory));
            statusBar.setFileNumber(indexInDirectory+1, filesInDirectory.size());
            updateFrame();
        } catch (IOException e) {
            InformationDialog.showErrorDialog(this, Translator.get("file_viewer.view_error_title"), Translator.get("file_viewer.view_error"));
            e.printStackTrace();
        }
    }


    private static String colorToRgbStr(int color) {
        int a = (color >> 24) & 0xff;
        int r = (color >> 16) & 0xff;
        int g = (color >> 8) & 0xff;
        int b = (color) & 0xff;
        String result = r + ", " + g + ", " + b;
        if (a == 0xff) {
            result = "RGB: (" + result + ")";
        } else {
            result = a + ", " + result;
            result = "ARGB: (" + result + ")";
        }
        return result;
    }

    private static String colorToHexStr(int color) {
        int a = (color >> 24) & 0xff;
        if (a == 0xff) {
            color &= 0x00ffffff;
        }
        String result = Integer.toHexString(color);
        while (result.length() < 6) {
            result = '0' + result;
        }
        if (result.length() == 7) {
            result = '0' + result;
        }
        return '#' + result.toUpperCase();
    }



    /**
     * Image viewer panel
     */
    private class ImageViewerImpl extends JPanel implements MouseMotionListener, MouseListener, ThemeListener {

    	private Color backgroundColor;
    	
    	ImageViewerImpl() {
    		backgroundColor = ThemeManager.getCurrentColor(Theme.EDITOR_BACKGROUND_COLOR);
            ThemeManager.addCurrentThemeListener(this);
            addMouseListener(this);
            addMouseMotionListener(this);
        }
    	
    	////////////////////////
        // Overridden methods //
        ////////////////////////

        @Override
        public void paint(Graphics g) {
            int width = getWidth();
            int height = getHeight();

            g.setColor(backgroundColor);
            g.fillRect(0, 0, width, height);

            if (scaledImage != null) {
                int imageWidth = scaledImage.getWidth();
                int imageHeight = scaledImage.getHeight();
                g.drawImage(scaledImage, Math.max(0, (width-imageWidth)/2), Math.max(0, (height-imageHeight)/2), null);
            }
        }
        
        @Override
        public synchronized Dimension getPreferredSize() {
            return new Dimension(scaledImage.getWidth(), scaledImage.getHeight());
        }
    	
    	//////////////////////////////////
        // ThemeListener implementation //
        //////////////////////////////////

        /**
         * Receives theme color changes notifications.
         */
        public void colorChanged(ColorChangedEvent event) {
            if(event.getColorId() == Theme.EDITOR_BACKGROUND_COLOR) {
                backgroundColor = event.getColor();
                repaint();
            }
        }


        @Override
        public void mouseMoved(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();

            final int imageOffsetX = Math.max(0, (imageViewerImpl.getWidth()-scaledImage.getWidth())/2);
            final int imageOffsetY = Math.max(0, (imageViewerImpl.getHeight()-scaledImage.getHeight())/2);

            boolean inImageArea = x >= imageOffsetX && y >= imageOffsetY && x < imageOffsetX + scaledImage.getWidth() && y < imageOffsetY + scaledImage.getHeight();
            if (inImageArea) {
                setFrameCursor(CURSOR_CROSS);
            } else {
                setFrameCursor(CURSOR_DEFAULT);
            }
        }


        @Override
        public void mouseClicked(MouseEvent e) {
            int x = e.getX();
            int y = e.getY();

            final int w = scaledImage.getWidth();
            final int h = scaledImage.getHeight();
            final int imageOffsetX = Math.max(0, (imageViewerImpl.getWidth()-w)/2);
            final int imageOffsetY = Math.max(0, (imageViewerImpl.getHeight()-h)/2);

            int pixelX = x - imageOffsetX;
            if (pixelX < 0 || pixelX >= w) {
                return;
            }
            int pixelY = y - imageOffsetY;
            if (pixelY < 0 || pixelY >= h) {
                return;
            }
            pixelX = (int)(pixelX/zoomFactor);
            pixelY = (int)(pixelY/zoomFactor);
            int color = image.getRGB(pixelX, pixelY);
            int r = (color >> 16) & 0xff;
            int g = (color >> 8) & 0xff;
            int b = (color) & 0xff;
            statusBar.setStatusMessage("XY: (" +pixelX + ", " + pixelY + ")  " + colorToRgbStr(color) + "  HTML: (" + colorToHexStr(color) + ")");
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setFrameCursor(CURSOR_DEFAULT);
        }




        /**
         * Not used, implemented as a no-op.
         */
        @Override
        public void fontChanged(FontChangedEvent event) {}

        @Override
        public void mouseDragged(MouseEvent e) {
//            mouseMoved(e);
        }


        @Override
        public void mousePressed(MouseEvent e) {}

        @Override
        public void mouseReleased(MouseEvent e) {}

        @Override
        public void mouseEntered(MouseEvent e) {}
    }
}
