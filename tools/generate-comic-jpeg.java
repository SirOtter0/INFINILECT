// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright © 2026 SirOtter0 and INFINILECT contributors.
// Offline original fixture generator. JDK source launcher; prints Base64, never used by app.
import java.awt.*; import java.awt.image.*; import javax.imageio.*; import java.io.*; import java.util.*;
class Art {
 public static void main(String[] args) throws Exception {
  BufferedImage img=new BufferedImage(640,640,BufferedImage.TYPE_INT_RGB);
  Graphics2D g=img.createGraphics();g.setColor(new Color(245,236,205));g.fillRect(0,0,640,640);
  for(int i=0;i<3;i++){g.setColor(Color.BLACK);g.fillRect(30,30+i*198,580,180);g.setColor(new Color(90+i*40,155,205-i*30));g.fillRect(36,36+i*198,568,168);g.setColor(new Color(240,200,85));g.fillOval(80+i*110,55+i*198,100,100);g.setColor(Color.WHITE);g.fillRect(280,105+i*198,250,40);}
  g.dispose(); ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(img,"jpeg",out);System.out.print(Base64.getEncoder().encodeToString(out.toByteArray()));
 }
}
