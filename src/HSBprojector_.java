import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.ZProjector;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.IntStream;


/*
動機 :
    FRETにおいてIMD表示を行い、RGBへ変換したものをSUM projectionを行っていた。
    どうしてもこれが気持ち悪い。(ratioのsumではなくRGBの各値のsumになるため)
    Ratioをもとにsumおよびaverageなどで求めるべきと考えたため。

20190814
とりあえずは RGB画像(HSB由来)からRatioを導き、平均化。
その値をさらにHSBのHとして用いSBはそれぞれmax,minから比を求める。
Sについてはさらに1.5倍行い高彩度化をこころみる。 ->この項により単純なSum画像における低彩度を引き上げる処理にも使用可能

20190819
Sについては1.5倍を廃止し、heuの値より算出された値を用いる。-> Sが大きすぎると画像が潰れることを回避するため。
代わりにextend valueを追加し、ratioに対してかけることでheuの値を変化させ色を変える。

20191030
embryo周縁部のratioが高く表現されている
おそらく黒などのくらい部分のratioおhueへの変換時に高くなっているのではと考える。
embryo中心部が明るいうす紫になるため目立ってしまう。
 ->どうやら前のバージョンで問題は解消していたようで、我々が使用していたのが古かったのかもしれない
 ->20191101どうやらAutoCutBを行うと出ることが判明。やはり最初の予想通りかもしれない


20191113
HSB変換後,B > 任意の値(default 0.1)で各値を累積かつ使用した数を数える。
枚数 > 1　で各累積値/ 枚数　を行って平均値を出す。
また、ROI内でMin, Maxを取って比を取る方法にすることでlocalでのより細かい変化が見られる。
ただし、飽和する部分が出るため注意が必要である。

*/

public class HSBprojector_ implements PlugIn {
    private GenericDialog gd;


    static String title = "HSBprojector_ver.beta(20191113)"; // いずれ PluginFrameにかえるか？

    ImagePlus mainImage;

    int stackSize;
    int nChannels;
    int nSlices;
    int nFrames;

    float heuRange = 270.0f / 360.0f;
    float initialHeuRange = 1.0f - heuRange;

    private double limitValue = 0.0;
    private double cutOffForAveValue = 0.1;

    Roi roi;


    @Override
    public void run(String arg) {

        if(this.getCurrentImage() == true){
            if(openDialog()) {
                limitValue = gd.getNextNumber();
                cutOffForAveValue = gd.getNextNumber();

                stackSize = mainImage.getStackSize();
                nChannels = mainImage.getNChannels();
                nSlices = mainImage.getNSlices();
                nFrames = mainImage.getNFrames();

                roi = mainImage.getRoi();

                this.createProjectionForFrame();

                //this.createAndIncreaseSaturation();
            }
        }else{
            IJ.showMessage("RGB only");
            return;
        }
    }

    private boolean openDialog(){


        gd = new GenericDialog("HSBprojector", IJ.getInstance());
        gd.addNumericField("Extend value", limitValue, 2);
        gd.addNumericField("CutOffForAve", cutOffForAveValue, 2);
        gd.showDialog();

        if (gd.wasCanceled()) {
            return false;
        }else {

            return true;
        }
    }


    public boolean getCurrentImage(){
        boolean b = false;
        mainImage = IJ.getImage();
        if((mainImage != null)&&(mainImage.getBitDepth() == 24)){ //RGBのチェックいる
            b = true;
        }
        return b;
    }

    public void createProjectionForFrame(){

        ArrayList<ImagePlus> buffArray = new ArrayList<>();
        for(int i = 0; i < nFrames; i++){
            buffArray.add(new ImagePlus());
        }
        IntStream intStream = IntStream.range(0, nFrames);
        ArrayList<Integer> count = new ArrayList<>();
        intStream.parallel().forEach(t ->{
            count.add(1);
            IJ.showStatus("Processing..." + count.size() + "  / " + nFrames);
            Duplicator dp = new Duplicator();
            mainImage.killRoi();
            ImagePlus buff = dp.run(mainImage, 1, 1, 1, nSlices, t+1, t+1);
            mainImage.setRoi(roi);
            buffArray.get(t).setImage(this.createProjection(buff));
        });

        ImageStack imageStack = new ImageStack(mainImage.getWidth(), mainImage.getHeight());
        buffArray.forEach(img ->{
            imageStack.addSlice(img.getProcessor());
        });

        ImagePlus resultImage = new ImagePlus();
        String title = "HSBprojection_" + "L" + limitValue + "_C" + cutOffForAveValue + "_" + mainImage.getTitle();
        resultImage.setStack(title, imageStack);
        resultImage.setCalibration(mainImage.getCalibration());
        resultImage.setFileInfo(mainImage.getOriginalFileInfo());
        resultImage.setDimensions(1, 1, nFrames);
        resultImage.setRoi(roi);
        resultImage.show();
    }


    public ImagePlus createProjection(ImagePlus zStackImage){

        ColorProcessor resultCP = new ColorProcessor(zStackImage.getWidth(), zStackImage.getHeight());

        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> ratioMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> intensityMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> saturationMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> adoptionMap = new ConcurrentHashMap<>(); //平均計算において採用したスライスの数を格納


        for(int x = 0; x < zStackImage.getWidth(); x++) {
            ConcurrentHashMap<Integer, Double> buffMapH = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, Double> buffMapS = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, Double> buffMapB = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, Double> buffMapA = new ConcurrentHashMap<>();

            for (int y = 0; y < zStackImage.getHeight(); y++) {
                buffMapH.put(y, 0.0);
                buffMapS.put(y, 0.0);
                buffMapB.put(y, 0.0);
                buffMapA.put(y, 0.0);

            }
            ratioMap.put(x, buffMapH);
            intensityMap.put(x, buffMapS);
            saturationMap.put(x, buffMapB);
            adoptionMap.put(x, buffMapA);

        }

        IntStream intStream = IntStream.range(0, nSlices);
        intStream.parallel().forEach(i ->{
            for(int x = 0; x < zStackImage.getWidth(); x++){
                for(int y = 0; y < zStackImage.getHeight(); y++){
                    ColorProcessor cp = (ColorProcessor) zStackImage.getStack().getProcessor(i + 1);
                    Color pixelColor = cp.getColor(x, y);
                    int r = pixelColor.getRed();
                    int g = pixelColor.getGreen();
                    int b = pixelColor.getBlue();

                    float[] hsb = new float[3];
                    Color.RGBtoHSB(r, g, b, hsb);

                    //System.out.println("hsb[] : " + hsb[0] + "," + hsb[1] + "," + hsb[2]);


                    /*
                    //Max

                    float trueRatio = 1.0f - hsb[0];
                    if(trueRatio == 1.0f) trueRatio = 0.0f;
                    //System.out.println("trueRatio " + trueRatio);
                    if(trueRatio > ratioMap.get(x).get(y)){
                        ratioMap.get(x).replace(y, (double)trueRatio);
                        saturationMap.get(x).replace(y, (double)hsb[1]);
                        intensityMap.get(x).replace(y, (double)hsb[2]);
                    }else if(trueRatio == ratioMap.get(x).get(y)){//ratioは同じでもintensityの違いがある可能性
                        //ratioMap.get(x).replace(y, hsb[0]);
                        if(hsb[2] > intensityMap.get(x).get(y)) {
                            saturationMap.get(x).replace(y, (double)hsb[1]);
                            intensityMap.get(x).replace(y, (double)hsb[2]);
                        }

                    }
                    */


                    /*
                    //累積 //現状これが一番良さげ
                    double buffValue = 1.0 - hsb[0];
                    //if(hsb[0] == 0.0) buffValue = 0.0;
                    double hA = ratioMap.get(x).get(y) + buffValue;
                    double sA = saturationMap.get(x).get(y) + hsb[1];
                    double bA = intensityMap.get(x).get(y) + hsb[2];
                    ratioMap.get(x).replace(y, hA);
                    saturationMap.get(x).replace(y, sA);
                    intensityMap.get(x).replace(y, bA);
                    */


                    ///*平均

                    if(hsb[2] > cutOffForAveValue) {

                        double buffValue = 1.0- hsb[0];
                        double hA = ratioMap.get(x).get(y) + buffValue;
                        double sA = saturationMap.get(x).get(y) + hsb[1];
                        double bA = intensityMap.get(x).get(y) + hsb[2];
                        double aA = adoptionMap.get(x).get(y) + 1.0;
                        ratioMap.get(x).replace(y, hA);
                        saturationMap.get(x).replace(y, sA);
                        intensityMap.get(x).replace(y, bA);
                        adoptionMap.get(x).replace(y, aA);
                    }

                    //*/


                }

            }
        });


        ///* //平均値を採用されたスライスのみで作る場合に使用
        //ここにratioMapを先に平均値にする。(adoptionMapで割る)
        IntStream forAveStream = IntStream.range(0, nSlices);
            for(int x = 0; x < zStackImage.getWidth(); x++){
                for(int y = 0; y < zStackImage.getHeight(); y++){
                    double aA = adoptionMap.get(x).get(y);

                    //System.out.println("a , h = " + aA + ", " + intensityMap.get(x).get(y));

                    if(aA > 1.0) {
                        double hA = ratioMap.get(x).get(y) / aA;
                        double sA = saturationMap.get(x).get(y) / aA;
                        double bA = intensityMap.get(x).get(y) / aA;


                        ratioMap.get(x).replace(y, hA);
                        saturationMap.get(x).replace(y, sA);
                        intensityMap.get(x).replace(y, bA);

                    }else{ //1枚以下は値を0にする？
                        ratioMap.get(x).replace(y, 0.0);
                        saturationMap.get(x).replace(y, 0.0);
                        intensityMap.get(x).replace(y, 0.0);
                    }

                }
            }




        //*/



        double[] hMinMax = this.getMaxAndMinValue(ratioMap);
        double[] sMinMax = this.getMaxAndMinValue(saturationMap);
        double[] bMinMax = this.getMaxAndMinValue(intensityMap);

        //double hMaxAve = hMinMax[0] / nSlices;
        //double hMinAve = hMinMax[1] / nSlices;


        //System.out.println("max, min : " + hMaxAve + ", " + hMinAve);

        for(int x = 0; x < zStackImage.getWidth(); x++) {
            for (int y = 0; y < zStackImage.getHeight(); y++) {
                //Color c = Color.getHSBColor(1.0f - (ratioMap.get(x).get(y).floatValue() / nSlices), saturationMap.get(x).get(y).floatValue() / nSlices, intensityMap.get(x).get(y).floatValue() / nSlices);

                //Color c = Color.getHSBColor(ratioMap.get(x).get(y).floatValue(), saturationMap.get(x).get(y).floatValue(), intensityMap.get(x).get(y).floatValue());

                /* //平均などの場合はこの部分使用
                //Color c = Color.getHSBColor(ratioMap.get(x).get(y) / stackSize, saturationMap.get(x).get(y)/stackSize, intensityMap.get(x).get(y)/stackSize);
                //float buffH = (heuRange * (float)((ratioMap.get(x).get(y) - hMinMax[1]) / (hMinMax[0] - hMinMax[1])));

                //float buffH = (float)((ratioMap.get(x).get(y) / nSlices));
                //float buffH = dividedRatioMap.get(x).get(y).floatValue();

                //float buffH = (float)(((ratioMap.get(x).get(y) / nSlices) - hMinAve) / (hMaxAve - hMinAve));
                */



                ///* //累積から比を求める場合はこれ
                float buffH = (float)((ratioMap.get(x).get(y) - hMinMax[1]) / (hMinMax[0] - hMinMax[1])); //累積を用いてRatioを再計算するので平均を先に計算する意味はない(SUMになるのか？)
                //*/


                //全体的に数値をかけることで表示される色合いを変化させる
                if(limitValue != 0.0){
                    buffH = (float)(buffH * limitValue);
                }
                //System.out.println(buffH);

                if(buffH < 0){
                    buffH = 0;
                }
                //buffH = buffH + initialHeuRange;
                if(buffH > 1){
                    buffH = 1.0f;
                }

                float h = 1.0f - buffH;
                //float h = buffH;

                float s = (float)((saturationMap.get(x).get(y) - sMinMax[1]) / (sMinMax[0] - sMinMax[1]));
                if(s < 0) s = 0;
                if(s > 1.0f) s = 1.0f;

                float b = (float)((intensityMap.get(x).get(y) - bMinMax[1]) / (bMinMax[0] - bMinMax[1]));
                if(b > 1.0f) b = 1.0f;
                if(b < 0) b = 0;

                Color c = Color.getHSBColor(h, 1.0f - h, b); //sの値はratiioによって決めるほうがいいかも //平均の場合
                //Color c = Color.getHSBColor(h, s, b); //20191101 黒色を表現するためにはsを小さい値にする必要がある。
                //Color c = Color.getHSBColor(h, 1.0f - buffH, b); //sの値はratiioによって決めるほうがいいかも //Maxの場合

                //*/

                //System.out.println("c hsb : " + ratioMap.get(x).get(y) / stackSize + ", " + saturationMap.get(x).get(y) / stackSize + ", "+ intensityMap.get(x).get(y)/stackSize);
                resultCP.set(x, y, c.getRGB());
            }
        }

        //System.out.println("hMinMax : " + hMinMax[0] + ", " + hMinMax[1]);
        //System.out.println("sMinMax : " + sMinMax[0] + ", " + sMinMax[1]);
        //System.out.println("bMinMax : " + bMinMax[0] + ", " + bMinMax[1]);

        ImagePlus result = new ImagePlus();
        result.setProcessor(resultCP);
        result.setTitle("HSBprojector");
        result.setFileInfo(mainImage.getOriginalFileInfo());
        result.setCalibration(mainImage.getCalibration());
        return result;

    }

    public void createAndIncreaseSaturation(){
        ZProjector zProjector = new ZProjector();
        zProjector.setImage(mainImage);
        zProjector.setMethod(ZProjector.SUM_METHOD);

        //sd結構いいかも。Calc4でできた画像をmedianしてから使用するとより良い(中間値のshotnoiseらしきものがあるため)でも画像が鈍くなる。。。
        zProjector.doRGBProjection();
        ImagePlus projectedImage = zProjector.getProjection();

        ImagePlus result = this.increaseSatulation(projectedImage);

        result.show();
    }

    public ImagePlus increaseSatulation(ImagePlus projectedImage){
        ColorProcessor resultcp = new ColorProcessor(projectedImage.getWidth(), projectedImage.getHeight());
        ColorProcessor cp = (ColorProcessor) projectedImage.getProcessor();

        for(int x = 0; x < projectedImage.getWidth(); x++){
            for(int y = 0; y < projectedImage.getHeight(); y++){
                Color pixelColor = cp.getColor(x, y);
                int r = pixelColor.getRed();
                int g = pixelColor.getGreen();
                int b = pixelColor.getBlue();

                float[] hsb = new float[3];
                Color.RGBtoHSB(r, g, b, hsb);

                float increaseValue = hsb[1] * 1.5f;
                if(increaseValue > 1.0f){
                    increaseValue = 1.0f;
                }

                Color c = Color.getHSBColor(hsb[0], increaseValue, hsb[2]);

                resultcp.set(x, y, c.getRGB());

            }
        }

        ImagePlus result = new ImagePlus();
        result.setProcessor(resultcp);
        result.setTitle("SatulationIncreased");
        return result;
    }

    public double[] getMaxAndMinValue(ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> valueData){


        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> valueMap = valueData;

        if((roi != null)&&(roi.isArea())){
            double startX = roi.getXBase();
            double startY= roi.getYBase();
            double xWidth = roi.getFloatWidth();
            double yHeight = roi.getFloatHeight();
            valueMap = this.cropHashMap(valueData, (int)startX, (int)startY, (int)xWidth, (int)yHeight);
        }


        ArrayList<Double> valueArrayMax = new ArrayList<>();
        valueMap.forEach((x, yMap) ->{
            valueArrayMax.add(yMap.values().stream().max(Comparator.naturalOrder()).get());
        });

        ArrayList<Double> valueArrayMin = new ArrayList<>();
        valueMap.forEach((x, yMap) ->{
            valueArrayMin.add(yMap.values().stream().min(Comparator.naturalOrder()).get());
        });

        double[] result = new double[2];
        result[0] = valueArrayMax.stream().parallel().max(Comparator.naturalOrder()).get().doubleValue();
        result[1] = valueArrayMin.stream().parallel().min(Comparator.naturalOrder()).get().doubleValue();
        return result;
    }

    public ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> cropHashMap(ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> valueMap, int startX, int startY, int width, int height){
        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> resultMap = new ConcurrentHashMap<>();
        IntStream intStream = IntStream.range(0, width);

        intStream.parallel().forEach(x ->{
        //for(int x = 0; x < width; x++){
            ConcurrentHashMap<Integer, Double> buffmap = new ConcurrentHashMap<>();
            for(int y = 0; y < width; y++){
                buffmap.put(y, valueMap.get(x + startX).get(startY + y));
            }
            resultMap.put(x, buffmap);
        //}
        });
        return resultMap;
    }



    public ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> allDivide(ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> valueMap, double d){
        ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Double>> result = new ConcurrentHashMap<>();
        valueMap.forEach((x, yMap) ->{
            ConcurrentHashMap<Integer, Double> buff = new ConcurrentHashMap<>();
            yMap.forEach((y, value) -> {
                buff.put(y, value / d);
            });
            result.put(x, buff);
        });

        return result;
    }


}
