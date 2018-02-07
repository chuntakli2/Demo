package com.apps.android.demo;

import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ViewPager viewPager = this.findViewById(R.id.viewPager);
        viewPager.setBackgroundColor(getResources().getColor(R.color.colorPrimaryDark));
    }
}
