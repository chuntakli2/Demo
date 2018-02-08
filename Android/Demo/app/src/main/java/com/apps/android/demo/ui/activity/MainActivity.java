package com.apps.android.demo.ui.activity;

import android.os.Bundle;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

import com.apps.android.demo.R;
import com.synnapps.carouselview.CarouselView;
import com.synnapps.carouselview.CirclePageIndicator;
import com.synnapps.carouselview.ImageClickListener;
import com.synnapps.carouselview.ImageListener;

public class MainActivity extends AppCompatActivity {

    CarouselView viewPager;
    int[] images = {R.drawable.image_1, R.drawable.image_2, R.drawable.image_3, R.drawable.image_4, R.drawable.image_5};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewPager = this.findViewById(R.id.viewpager);
        /* CarouselView */
        viewPager.setSlideInterval(0);
        viewPager.addOnPageChangeListener(new ViewPager.OnPageChangeListener() {
            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
//                if (displayCarouselCourses != null && displayCarouselCourses.size() > 1 &&  displayCarouselCourses.size() == position + 1) {
//                    if (viewPager != null) {
//                        CarouselViewPager innerViewPager = (CarouselViewPager) viewPager.findViewById(com.synnapps.carouselview.R.id.containerViewPager);
//                        innerViewPager.setCurrentItem(0, false);
//                    }
//                }
            }

            @Override
            public void onPageSelected(int position) {

            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager.SCROLL_STATE_IDLE) {
//                    if (displayCarouselCourses != null && displayCarouselCourses.size() > 1) {
//                        new Handler().postDelayed(new Runnable() {
//                            @Override
//                            public void run() {
//                                if (viewPager != null) {
//                                    viewPager.playCarousel();
//                                }
//                            }
//                        }, CAROUSEL_DELAY_TIME);
//                    }
                }
            }
        });
        CirclePageIndicator indicator = (CirclePageIndicator) viewPager.findViewById(R.id.indicator);
        if (indicator != null){
            indicator.setVisibility(View.GONE);
        }

        viewPager.setImageListener(new ImageListener() {
            @Override
            public void setImageForPosition(int position, ImageView imageView) {
                imageView.setImageResource(images[position]);
                imageView.setScaleType(ImageView.ScaleType.FIT_XY);
            }
        });
        viewPager.setImageClickListener(new ImageClickListener() {
            @Override
            public void onClick(int position) {

            }
        });
        viewPager.setPageCount(5);
        viewPager.setCurrentItem(0);
    }
}
